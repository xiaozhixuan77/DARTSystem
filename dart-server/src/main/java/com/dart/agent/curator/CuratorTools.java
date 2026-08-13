package com.dart.agent.curator;

import com.dart.agent.IntentKeys;
import com.dart.agent.scoring.ScoredResource;
import com.dart.util.SseEvents;
import com.dart.util.SseUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * Curator 工具集 — Jaccard 去重、分节 LLM 报告生成、输出校验、降级兜底。
 */
public class CuratorTools {

    private static final Logger log = LoggerFactory.getLogger(CuratorTools.class);

    private static final int MIN_REPORT_LENGTH = 800;
    private static final int MAX_REPORT_LENGTH = 16000;
    private static final int MAX_CONTENT_PER_RESOURCE = 1500;
    private static final int MAX_TOKENS_PER_SECTION = 4096;

    /** 占位节标记（沉淀检查/降级判定引用同一标记，防文案改动静默失效） */
    public static final String SECTION_FAILED_MARKER = "本节内容生成失败";

    private static final String SECTION_FAILED =
            "> ⚠️ " + SECTION_FAILED_MARKER + "，请稍后重试或访问参考文献了解详情。";

    // 查询意图 → 报告风格要求（注入每节 prompt 首部；未知意图不加）
    private static final Map<String, String> INTENT_STYLE = Map.of(
            IntentKeys.QUICK_FACT, "报告风格：简洁定义式回答，直击要点，避免冗长铺垫",
            IntentKeys.HOW_TO, "报告风格：操作步骤式教程，步骤清晰可执行",
            IntentKeys.DEEP_DIVE, "报告风格：深入原理与源码级分析，讲清底层机制",
            IntentKeys.COMPARISON, "报告风格：对比分析，用表格呈现差异与适用场景",
            IntentKeys.TROUBLESHOOTING, "报告风格：诊断排错式，从现象到原因再到解决方案");

    private final ChatModel chatModel;
    private final double jaccardThreshold;
    private final int deepReadCount;

    public CuratorTools(ChatModel chatModel, double jaccardThreshold) {
        this(chatModel, jaccardThreshold, 4);
    }

    public CuratorTools(ChatModel chatModel, double jaccardThreshold, int deepReadCount) {
        this.chatModel = chatModel;
        this.jaccardThreshold = jaccardThreshold;
        this.deepReadCount = deepReadCount;
    }

    /**
     * 基于标题 Jaccard 相似度去重，高分保留。
     */
    public List<ScoredResource> deduplicate(List<ScoredResource> resources) {
        if (resources == null || resources.isEmpty()) {
            return List.of();
        }
        if (resources.size() == 1) {
            return new ArrayList<>(resources);
        }

        List<ScoredResource> sorted = new ArrayList<>(resources);
        sorted.sort(Comparator.comparingDouble(ScoredResource::getWeightedScore).reversed());

        // 预计算 shingle 集，避免 O(n²) 次重复计算
        Map<ScoredResource, Set<String>> shingleCache = new HashMap<>();
        for (ScoredResource r : sorted) {
            shingleCache.put(r, shingle(r.getTitle()));
        }

        List<ScoredResource> result = new ArrayList<>();
        for (ScoredResource r : sorted) {
            boolean duplicate = false;
            Set<String> shinglesA = shingleCache.get(r);
            for (ScoredResource kept : result) {
                double sim = jaccardSimilarity(shinglesA, shingleCache.get(kept));
                if (sim >= jaccardThreshold) {
                    duplicate = true;
                    break;
                }
            }
            if (!duplicate) {
                result.add(r);
            }
        }
        return result;
    }

    /**
     * 分节生成结构化 Markdown 学习报告：
     * ①核心概念（token 级流式直推，感知最快）→ ②原理+代码示例 → ③误区FAQ+进阶+参考文献。
     * 节 1 流式推送的同时，节 2/3 并行预生成缓冲，完成后按序整块推送。
     */
    public CuratedReport generateReport(String query, List<ScoredResource> resources, SseEmitter emitter) {
        return generateReport(query, resources, emitter, null);
    }

    /**
     * @param styleHint 查询意图（风格要求）；null/未知意图不注入风格句
     */
    public CuratedReport generateReport(String query, List<ScoredResource> resources,
                                        SseEmitter emitter, String styleHint) {
        return generateReport(query, resources, emitter, styleHint, null);
    }

    /**
     * @param styleHint 查询意图（风格要求）；null/未知意图不注入风格句
     * @param sessionSummary 会话滚动摘要（长记忆）：注入节 prompt 延续前文叙述；null 表示无历史
     */
    public CuratedReport generateReport(String query, List<ScoredResource> resources,
                                        SseEmitter emitter, String styleHint, String sessionSummary) {
        if (resources == null || resources.isEmpty()) {
            return emptyReport(query);
        }

        // 去重后仅剩 1 个 → 跳过 LLM
        if (resources.size() == 1) {
            return singleResourceReport(query, resources.getFirst());
        }

        String resourcesText = buildResourcesText(resources);
        List<String> sections = new ArrayList<>();

        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            // 阶段 A：节 1（token 流式直推）+ 节 2 并行（峰值 2 并发——
            // 官网对并发长输入偶发空响应，2 并发实测 0 空，3 并发 ~1/3 空）
            SseUtils.sendStatus(emitter, "curating", "正在讲解核心概念...");
            Future<String> section2Future = pool.submit(() -> {
                SseUtils.sendStatus(emitter, "curating", "正在解析原理与代码示例...");
                return generateSection(query, resourcesText,
                        buildPrincipleCodePrompt(query), "principles_code", styleHint, sessionSummary);
            });

            String section1 = streamSection(query, resourcesText,
                    buildConceptPrompt(query), "concepts", emitter, styleHint, sessionSummary);
            sections.add(section1);

            String section2 = section2Future.get();
            sections.add(section2);
            if (!section2.equals(SECTION_FAILED)) {
                SseUtils.sendEvent(emitter, SseEvents.CONTENT, section2);
            }

            // 阶段 B：节 3 单独生成（避免与节 2 并发造成 3 并发峰值）
            SseUtils.sendStatus(emitter, "curating", "正在整理误区与进阶方向...");
            String section3 = generateSection(query, resourcesText,
                    buildFaqAdvancePrompt(query), "faq_advance", styleHint, sessionSummary);
            sections.add(section3);
            if (!section3.equals(SECTION_FAILED)) {
                SseUtils.sendEvent(emitter, SseEvents.CONTENT, section3);
            }
        } catch (Exception e) {
            log.warn("并行分节生成异常，追加占位节", e);
            while (sections.size() < 3) {
                sections.add(SECTION_FAILED);
            }
        }

        // 所有节全部失败 → 降级为纯搜索结果列表，推送 fallback 报告
        if (sections.stream().allMatch(s -> s.equals(SECTION_FAILED))) {
            log.warn("所有报告节生成失败，降级使用 fallbackReport");
            CuratedReport fallback = fallbackReport(query, resources);
            SseUtils.sendEvent(emitter, SseEvents.CONTENT, fallback.markdown());
            return fallback;
        }

        // 失败节用资源摘要节兜底（按语义标题），保证报告不缺节
        String[] fallbackTitles = {"📖 核心概念", "🔬 关键原理", "⚠️ 常见误区与 FAQ"};
        for (int i = 0; i < sections.size(); i++) {
            if (sections.get(i).equals(SECTION_FAILED)) {
                String digest = buildFallbackSection(query, resources, fallbackTitles[i]);
                sections.set(i, digest);
                SseUtils.sendEvent(emitter, SseEvents.CONTENT, digest);
            }
        }

        // 节级代码块平衡修复：节内未闭合 fence 在节内闭合（拼接后才补会把后续节吞进代码块）
        List<String> balancedSections = sections.stream().map(this::ensureClosedCodeBlocks).toList();
        String markdown = truncateReport(ensureClosedCodeBlocks(String.join("\n\n---\n\n", balancedSections)));
        List<String> sources = resources.stream().map(ScoredResource::getUrl).toList();
        return new CuratedReport(query, markdown, sources, Instant.now());
    }

    /**
     * 调用 LLM 生成单节内容；失败或空白响应重试一次（DeepSeek 官网长输入并发时
     * 偶发 HTTP 200 + 空内容），仍失败返回占位文本，不影响主流程。
     */
    private String generateSection(String query, String resourcesText,
                                   String sectionPrompt, String sectionName, String styleHint,
                                   String sessionSummary) {
        ChatOptions options = ChatOptions.builder().maxTokens(MAX_TOKENS_PER_SECTION).build();
        String prompt = buildSectionPrompt(query, resourcesText, sectionPrompt, styleHint, sessionSummary);
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                ChatResponse response = chatModel.call(new Prompt(new UserMessage(prompt), options));
                // finish_reason=length/insufficient_system_resource：输出被服务端截断，内容不完整，
                // 视为失败重试（DeepSeek 截断不抛异常、非空白，旧逻辑会静默接受坏节）
                String finishReason = response.getResults().getFirst().getMetadata().getFinishReason();
                if (isTruncated(finishReason)) {
                    log.warn("报告节[{}]第 {} 次调用输出截断({})，重试", sectionName, attempt, finishReason);
                    continue;
                }
                String responseText = response.getResults().getFirst().getOutput().getText();
                String cleaned = cleanSection(responseText);
                if (!cleaned.isBlank()) {
                    return cleaned;
                }
                log.warn("报告节[{}]第 {} 次调用返回空白，重试", sectionName, attempt);
            } catch (Exception e) {
                log.warn("报告节[{}]第 {} 次调用失败", sectionName, attempt, e);
            }
        }
        return SECTION_FAILED;
    }

    /**
     * LLM 流式生成单节内容：每个 delta 立即推送 SSE CONTENT 事件（打字机效果），
     * 同时累积完整文本返回；空白响应重试一次（官网偶发空流），失败返回占位文本。
     */
    private String streamSection(String query, String resourcesText, String sectionPrompt,
                                 String sectionName, SseEmitter emitter, String styleHint,
                                 String sessionSummary) {
        ChatOptions options = ChatOptions.builder().maxTokens(MAX_TOKENS_PER_SECTION).build();
        String prompt = buildSectionPrompt(query, resourcesText, sectionPrompt, styleHint, sessionSummary);
        for (int attempt = 1; attempt <= 3; attempt++) {
            StringBuilder sb = new StringBuilder();
            try {
                // 截断响应不做流式重试（token 已推送无法回滚），记录后交给代码块平衡兜底
                AtomicReference<String> finishReason = new AtomicReference<>();
                chatModel.stream(new Prompt(new UserMessage(prompt), options))
                        .doOnNext(resp -> {
                            finishReason.set(resp.getResults().getFirst().getMetadata().getFinishReason());
                            String delta = resp.getResults().getFirst().getOutput().getText();
                            if (delta == null) {
                                return;
                            }
                            // 空白 delta（空格/换行）必须同时进入拼接与 SSE 推送，
                            // 否则前端累积内容缺空格/换行，markdown 格式（"### 1."、列表换行）被破坏
                            sb.append(delta);
                            SseUtils.sendEvent(emitter, SseEvents.CONTENT, delta);
                        })
                        .blockLast();
                if (isTruncated(finishReason.get())) {
                    log.warn("报告节[{}]第 {} 次流式输出截断({})，内容不完整", sectionName, attempt, finishReason.get());
                }
                String cleaned = cleanSection(sb.toString());
                if (!cleaned.isBlank()) {
                    return cleaned;
                }
                log.warn("报告节[{}]第 {} 次流式返回空白，重试", sectionName, attempt);
            } catch (Exception e) {
                log.warn("报告节[{}]第 {} 次流式生成失败", sectionName, attempt, e);
            }
        }
        return SECTION_FAILED;
    }

    /** DeepSeek（OpenAI 兼容）内容截断标记：达 max_tokens / 服务端资源不足中断。 */
    private static boolean isTruncated(String finishReason) {
        return finishReason != null
                && (finishReason.equals("length") || finishReason.equals("insufficient_system_resource"));
    }

    /**
     * 输出校验失败后的单次 LLM 精简重写：基于素材生成 800-1200 字精炼报告
     * （标题 + 核心概念要点 + 关键资源 + 小结）。空白响应/异常返回 null，
     * 调用方据此决定是否降级 fallback——不引入重试循环。
     */
    public String regenerateReport(String query, List<ScoredResource> resources) {
        String resourcesText = buildResourcesText(resources);
        // 与 preProcessQuery 一致：XML 包裹 + < > 转义，防止用户输入注入 prompt 指令
        String sanitized = query.replace("<", "&lt;").replace(">", "&gt;");
        String prompt = """
                你是一个技术教育专家。以下搜索结果素材质量不足，无法生成完整分节报告。
                请基于这些素材，输出一份 800-1200 字的精炼学习报告，严格按 markdown 格式：

                # <user_query>%s</user_query> 精炼报告

                ## 核心概念
                3-5 个关键概念要点（每点 1-2 句）

                ## 关键资源
                列出 2-4 个最有价值资源的标题与 URL

                ## 小结
                3-4 句话总结核心结论

                只输出报告正文，不要输出其他内容。

                素材：
                %s
                """.formatted(sanitized, resourcesText);
        try {
            // 上限 4096：800-1200 字中文约 1500-2000 token，留余量防超长输出失控
            ChatOptions options = ChatOptions.builder().maxTokens(4096).build();
            String response = chatModel.call(new Prompt(new UserMessage(prompt), options))
                    .getResults().getFirst().getOutput().getText();
            String cleaned = response == null ? "" : response.trim();
            return cleaned.isBlank() ? null : cleaned;
        } catch (Exception e) {
            log.warn("报告单次重写失败，降级 fallback: query={}", query, e);
            return null;
        }
    }

    private String buildSectionPrompt(String query, String resourcesText,
                                      String sectionPrompt, String styleHint, String sessionSummary) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一个技术教育专家，正在撰写关于「").append(query)
                .append("」的深度学习报告。基于以下搜索结果素材撰写。\n\n");
        String style = styleHint == null ? null : INTENT_STYLE.get(styleHint);
        if (style != null) {
            sb.append(style).append("\n\n");
        }
        // 会话上下文（长记忆）：延续前文叙述、避免重复已讲内容；
        // 安全边界——摘要仅作上下文参考，引用来源必须来自本次素材
        if (sessionSummary != null && !sessionSummary.isBlank()) {
            sb.append("会话上下文（此前对话的滚动摘要，仅作延续叙述参考，"
                    + "不得作为引用来源，引用必须来自本次搜索素材，不得虚构来源）：\n")
                    .append("<session_memory>\n").append(sessionSummary)
                    .append("\n</session_memory>\n\n");
        }
        sb.append(sectionPrompt).append("\n\n---\n\n").append(resourcesText);
        return sb.toString();
    }

    private String buildConceptPrompt(String query) {
        return """
                请输出以下章节（只输出本节，不要输出其他章节）：

                ## 📖 核心概念

                从搜索素材中提取 3-5 个「%s」主题下的关键概念，每个概念展开 200-300 字：

                ### 1. 概念名
                - **定义**：一句话简明定义
                - **机制**：深入讲解其原理与内部工作方式（本部分为重点，要展开讲透）
                - **示例**：简短例子帮助理解
                - **关联**：与主题及其他概念的关系

                概念之间要有衔接过渡，读者学完后能独立理解该主题的知识骨架。
                本节字数控制在 1000-1200 字。
                """.formatted(query);
    }

    private String buildPrincipleCodePrompt(String query) {
        return """
                请输出以下两个章节（只输出本节，不要输出其他章节）：

                ## 🔬 关键原理

                选取 2-3 个「%s」核心技术原理，每个展开 150-250 字：
                - 用通俗语言解释，避免直接复制原文
                - 描述过程/步骤/机制（如执行流程、内部结构）
                - 信息不足处标注 [信息不足，建议访问原文]

                ## 💻 代码示例

                提供 1-2 个可直接运行的示例，每个包含：
                ### 示例N：标题
                - 代码块（标注语言类型，代码内加注释）
                - **讲解**：逐段解释关键代码的作用
                - **运行结果**：说明预期输出或效果

                若搜索素材缺少代码，根据常识补充完整可运行的示例。
                本节字数控制在 1000-1200 字（含代码）。
                """.formatted(query);
    }

    private String buildFaqAdvancePrompt(String query) {
        return """
                请输出以下三个章节（只输出本节，不要输出其他章节）：

                ## ⚠️ 常见误区与 FAQ

                - 2-3 个「%s」主题下开发者常犯的误区，每个格式：
                  **误区N：错误做法**
                  错误原因 + 正确做法
                - 1-2 个常见问答（**Q1: ...** / **A1: ...**）

                ## 🚀 进阶学习方向

                2-3 个与该主题相关的进阶方向，每个一行：
                - 方向名：一句话描述学习目标与路径

                ## 📚 参考文献

                列出本次报告使用的所有来源，格式：
                1. [标题](URL)

                本节字数控制在 1000-1200 字。
                """.formatted(query);
    }

    /**
     * 拼接资源文本：评分 Top deepReadCount 个资源携带完整内容（≤1500 字符），
     * 其余仅标题/URL/评分/一句话摘要（≤150 字符）——缩小 LLM 输入、加快首 token。
     */
    private String buildResourcesText(List<ScoredResource> resources) {
        StringBuilder sb = new StringBuilder();
        int idx = 1;
        for (ScoredResource r : resources) {
            sb.append("## 资源 ").append(idx).append(": ").append(r.getTitle()).append("\n");
            sb.append("- URL: ").append(r.getUrl()).append("\n");
            sb.append("- 评分: T").append(r.getScoreT())
                    .append("/A").append(r.getScoreA())
                    .append("/D").append(r.getScoreD())
                    .append("/M").append(r.getScoreM())
                    .append(" (总分 ").append(String.format("%.2f", r.getWeightedScore())).append(")\n");
            if (idx <= deepReadCount) {
                sb.append("- 内容摘要: ").append(truncateContent(r.getContent(), MAX_CONTENT_PER_RESOURCE)).append("\n\n");
            } else {
                sb.append("- 内容摘要: ").append(truncateContent(r.getContent(), 150)).append("\n\n");
            }
            idx++;
        }
        return sb.toString();
    }

    /**
     * 6 项输出校验。
     */
    public boolean validateReport(CuratedReport report) {
        if (report == null) return false;
        if (report.title() == null || report.title().isBlank()) return false;
        if (report.markdown() == null || report.markdown().length() < MIN_REPORT_LENGTH) return false;
        if (report.sources() == null || report.sources().isEmpty()) return false;
        if (report.generatedAt() == null) return false;
        // 至少包含一个 # 标题
        if (!report.markdown().contains("#")) return false;
        return true;
    }

    /**
     * 节生成失败的降级兜底：用搜索结果摘要拼接简易节（无 LLM 调用），保证报告不缺节。
     */
    private String buildFallbackSection(String query, List<ScoredResource> resources, String title) {
        StringBuilder sb = new StringBuilder();
        sb.append("## ").append(title).append("（AI 生成暂不可用，以下为相关资源摘要）\n\n");
        int idx = 1;
        for (ScoredResource r : resources) {
            sb.append("### ").append(idx).append(". ").append(r.getTitle()).append("\n");
            sb.append("- **来源**: ").append(r.getUrl()).append("\n");
            String snippet = truncateContent(r.getContent(), 300);
            if (!snippet.isBlank()) {
                sb.append("- **摘要**: ").append(snippet).append("\n");
            }
            sb.append("\n");
            idx++;
        }
        return sb.toString();
    }

    /**
     * 降级报告 —— 纯代码拼接，不依赖 LLM。
     */
    public CuratedReport fallbackReport(String query, List<ScoredResource> resources) {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(query).append(" 搜索结果\n\n");
        sb.append("> AI 报告生成暂时不可用，以下为按质量评分排序的原始搜索结果。\n\n");

        int rank = 1;
        for (ScoredResource r : resources) {
            sb.append("## ").append(rank).append(". ").append(r.getTitle())
                    .append(" ⭐").append(String.format("%.1f", r.getWeightedScore()))
                    .append("\n\n");
            sb.append("- **来源**: ").append(r.getUrl()).append("\n");
            sb.append("- **质量评分**: T:").append(r.getScoreT())
                    .append(" A:").append(r.getScoreA())
                    .append(" D:").append(r.getScoreD())
                    .append(" M:").append(r.getScoreM()).append("\n");
            sb.append("- **摘要**: ").append(truncateContent(r.getContent(), MAX_CONTENT_PER_RESOURCE)).append("\n\n");
            rank++;
        }

        List<String> sources = resources.stream().map(ScoredResource::getUrl).toList();
        return new CuratedReport(query, ensureClosedCodeBlocks(sb.toString()), sources, Instant.now());
    }

    /**
     * 计算两个文本的 Jaccard 相似度（基于 3-gram）。
     */
    double jaccardSimilarity(String a, String b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) {
            return 0.0;
        }
        return jaccardSimilarity(shingle(a), shingle(b));
    }

    private double jaccardSimilarity(Set<String> shinglesA, Set<String> shinglesB) {
        if (shinglesA.isEmpty() || shinglesB.isEmpty()) {
            return 0.0;
        }
        Set<String> intersection = new HashSet<>(shinglesA);
        intersection.retainAll(shinglesB);
        Set<String> union = new HashSet<>(shinglesA);
        union.addAll(shinglesB);
        return (double) intersection.size() / union.size();
    }

    private Set<String> shingle(String text) {
        Set<String> result = new HashSet<>();
        String t = text.toLowerCase().replaceAll("\\s+", " ").trim();
        for (int i = 0; i <= t.length() - 3; i++) {
            result.add(t.substring(i, i + 3));
        }
        return result;
    }

    /**
     * 代码块平衡兜底：行首 ``` 数量为奇数（存在未闭合代码块）时在末尾追加闭合，
     * 防止未闭合 fence 吞掉后续所有节（markdown 渲染进代码区域）。节级调用，非拼接级。
     */
    String ensureClosedCodeBlocks(String markdown) {
        long fences = markdown.lines().filter(l -> l.startsWith("```")).count();
        if ((fences & 1L) == 0L) {
            return markdown;
        }
        return markdown + (markdown.endsWith("\n") ? "" : "\n") + "```";
    }

    private String cleanSection(String markdown) {
        String cleaned = markdown.trim();
        // 移除以"根据搜索结果"等开头的行
        cleaned = cleaned.replaceFirst("^根据搜索结果[^\\n]*\\n*", "");
        cleaned = cleaned.replaceFirst("^根据提供的搜索结果[^\\n]*\\n*", "");
        return cleaned;
    }

    private String truncateReport(String markdown) {
        if (markdown == null || markdown.length() <= MAX_REPORT_LENGTH) return markdown;
        return markdown.substring(0, MAX_REPORT_LENGTH) + "\n\n> 报告已截断以控制长度。";
    }

    private static String truncateContent(String content, int maxLen) {
        if (content == null || content.isEmpty()) return "";
        if (content.length() <= maxLen) return content;
        return content.substring(0, maxLen) + "...";
    }

    private CuratedReport emptyReport(String query) {
        return new CuratedReport(query,
                "# " + query + "\n\n未找到相关内容，请尝试更换搜索关键词。",
                List.of(), Instant.now());
    }

    private CuratedReport singleResourceReport(String query, ScoredResource r) {
        String md = "# " + query + " 搜索结果\n\n"
                + "## " + r.getTitle() + "\n\n"
                + "- **来源**: " + r.getUrl() + "\n"
                + "- **内容**: " + truncateContent(r.getContent(), 1000) + "\n";
        return new CuratedReport(query, ensureClosedCodeBlocks(md), List.of(r.getUrl()), Instant.now());
    }
}
