package com.dart.agent.curator;

import com.dart.broadcast.SseSink;
import com.dart.agent.IntentKeys;
import com.dart.agent.scoring.ScoredResource;
import com.dart.util.SseEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Curator 工具集 — Jaccard 去重、分节 LLM 报告生成、输出校验、降级兜底。
 */
public class CuratorTools {

    private static final Logger log = LoggerFactory.getLogger(CuratorTools.class);

    private static final int MIN_REPORT_LENGTH = 800;
    private static final int MAX_REPORT_LENGTH = 16000;
    private static final int MAX_CONTENT_PER_RESOURCE = 1500;
    private static final int MAX_TOKENS_PER_SECTION = 8192;

    /** 占位节标记（沉淀检查/降级判定引用同一标记，防文案改动静默失效） */
    public static final String SECTION_FAILED_MARKER = "本节内容生成失败";

    /** 草稿快照节流间隔：流式中断后前端拉取草稿的更新粒度（5s 一次写库） */
    private static final long DRAFT_SNAPSHOT_INTERVAL_MS = 5_000L;

    /**
     * 单节 LLM 流式生成内部超时：先于 HTTP 层读超时（120s）熔断，
     * 防止流挂起（TCP 未断）时重试链无限等待。非 final 便于测试注入短超时。
     */
    private Duration streamTimeout = Duration.ofSeconds(90);

    /**
     * 草稿上下文：累积已生成内容（与前端流式推送顺序/内容一致），
     * 每 intervalMs 节流触发一次快照回调；null 时全部跳过（无草稿场景零开销）。
     */
    private static final class DraftContext {
        final StringBuilder content = new StringBuilder();
        final Consumer<String> consumer;
        final long intervalMs;
        long lastNotifyAt;

        DraftContext(Consumer<String> consumer, long intervalMs) {
            this.consumer = consumer;
            this.intervalMs = intervalMs;
        }

        void appendAndMaybeNotify(String s) {
            content.append(s);
            long now = System.currentTimeMillis();
            if (now - lastNotifyAt >= intervalMs) {
                lastNotifyAt = now;
                consumer.accept(content.toString());
            }
        }

        void appendAndNotify(String s) {
            content.append(s);
            notifyNow();
        }

        void notifyNow() {
            lastNotifyAt = System.currentTimeMillis();
            consumer.accept(content.toString());
        }
    }


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
     * 分节生成结构化 Markdown 学习报告（三节全部流式输出，打字机效果）：
     * ①核心概念（token 级流式直推）→ ②原理+代码示例（与节 1 并行流式生成进队列，
     * 节 1 完成后实时消费推送）→ ③误区FAQ+进阶+参考文献（流式直推）。
     *
     * @param styleHint 查询意图（风格要求）；null/未知意图不注入风格句
     * @param sessionSummary 会话滚动摘要（长记忆）：注入节 prompt 延续前文叙述；null 表示无历史
     * @param degraded true = 素材为搜索摘要级（抓取失败兜底），各节注入精简约束防虚构
     * @param draftConsumer 报告生成过程中的草稿快照回调（每 5s 节流 + 节完成时触发），
     *                      内容与前端流式推送顺序/内容一致；null 表示不回调。
     *                      用于流式中断后前端直接拉取已生成部分（无感续流）
     */
    public CuratedReport generateReport(String query, List<ScoredResource> resources,
                                        SseSink sink, String styleHint, String sessionSummary,
                                        boolean degraded, Consumer<String> draftConsumer) {
        if (resources == null || resources.isEmpty()) {
            return emptyReport(query);
        }

        String resourcesText = buildResourcesText(resources);
        List<String> sections = new ArrayList<>();
        // 草稿 = 前端流式已推送内容的忠实快照（节 1 token 直推 + 节 2 回放 + 节 3 直推，无分隔符）
        DraftContext draft = draftConsumer == null ? null
                : new DraftContext(draftConsumer, DRAFT_SNAPSHOT_INTERVAL_MS);

        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            // 阶段 A：节 1（token 流式直推）+ 节 2 并行流式生成进队列（峰值 2 并发——
            // 官网对并发长输入偶发空响应，2 并发实测 0 空，3 并发 ~1/3 空）
            sink.sendStatus("curating", "正在讲解核心概念...");
            Future<String> section2Future = pool.submit(() -> {
                sink.sendStatus("curating", "正在解析原理与代码示例...");
                return streamSectionToQueue(query, resourcesText,
                        buildPrincipleCodePrompt(query), "principles_code",
                        styleHint, sessionSummary, degraded);
            });

            String section1 = streamSection(query, resourcesText,
                    buildConceptPrompt(query), "concepts", sink, styleHint, sessionSummary, degraded, draft);
            sections.add(section1);
            if (draft != null) {
                draft.notifyNow();
            }

            // 节 1 完成后等节 2 生产端完整结束（含截断/空白重试，无竞态可安全重试），
            // 成功后分片回放模拟打字机（~30 字符/片，30ms 间隔）；超时中断后台生成按失败节兜底
            String section2;
            try {
                section2 = section2Future.get(streamTimeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                section2Future.cancel(true);
                log.warn("节 2 生成超时({}ms)，中断后台生成，按失败节兜底", streamTimeout.toMillis());
                section2 = SECTION_FAILED;
            }
            if (!section2.equals(SECTION_FAILED)) {
                pushInChunks(section2, sink);
                // 节 2 在回放期间预生成不推送，草稿于回放完成后同步（与前端看到内容一致）
                if (draft != null) {
                    draft.appendAndNotify(section2);
                }
            }
            sections.add(section2);

            // 阶段 B：节 3 流式直推（与节 2 串行，保持 2 并发峰值）
            sink.sendStatus("curating", "正在整理误区与进阶方向...");
            String section3 = streamSection(query, resourcesText,
                    buildFaqAdvancePrompt(query), "faq_advance", sink, styleHint, sessionSummary, degraded, draft);
            sections.add(section3);
            if (draft != null) {
                draft.notifyNow();
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
            sink.sendEvent(SseEvents.CONTENT, fallback.markdown());
            if (draft != null) {
                draft.appendAndNotify(fallback.markdown());
            }
            return fallback;
        }

        // 失败节用资源摘要节兜底（按语义标题），保证报告不缺节
        String[] fallbackTitles = {"📖 核心概念", "🔬 关键原理", "⚠️ 常见误区与 FAQ"};
        for (int i = 0; i < sections.size(); i++) {
            if (sections.get(i).equals(SECTION_FAILED)) {
                String digest = buildFallbackSection(query, resources, fallbackTitles[i]);
                sections.set(i, digest);
                sink.sendEvent(SseEvents.CONTENT, digest);
                if (draft != null) {
                    draft.appendAndNotify(digest);
                }
            }
        }

        // 节级代码块平衡修复：节内未闭合 fence 在节内闭合（拼接后才补会把后续节吞进代码块）
        List<String> balancedSections = sections.stream().map(this::ensureClosedCodeBlocks).toList();
        String markdown = truncateReport(ensureClosedCodeBlocks(String.join("\n\n---\n\n", balancedSections)));
        List<String> sources = resources.stream().map(ScoredResource::getUrl).toList();
        return new CuratedReport(query, markdown, sources, Instant.now());
    }

    /**
     * 节 2 并行流式生成（后台线程）：token 只累积不推送（避免与节 1 的推送交错），
     * 完整文本返回。截断（LENGTH）与空白响应均重试——调用方在节 1 完成后才拿结果，
     * 无消费者竞态，可安全重试；3 次全失败返回占位文本。
     */
    private String streamSectionToQueue(String query, String resourcesText, String sectionPrompt,
                                        String sectionName, String styleHint, String sessionSummary,
                                        boolean degraded) {
        ChatOptions options = ChatOptions.builder().maxTokens(MAX_TOKENS_PER_SECTION).build();
        String prompt = buildSectionPrompt(query, resourcesText, sectionPrompt,
                styleHint, sessionSummary, degraded);
        for (int attempt = 1; attempt <= 3; attempt++) {
            AtomicReference<String> finishReason = new AtomicReference<>();
            StringBuilder sb = new StringBuilder();
            try {
                chatModel.stream(new Prompt(new UserMessage(prompt), options))
                        .doOnNext(resp -> {
                            finishReason.set(resp.getResults().getFirst().getMetadata().getFinishReason());
                            String delta = resp.getResults().getFirst().getOutput().getText();
                            if (delta == null) {
                                return;
                            }
                            sb.append(delta);
                        })
                        .timeout(streamTimeout)
                        .blockLast();
                if (isTruncated(finishReason.get())) {
                    // 节 2 预生成无实时消费，截断可安全重试（调用方等完整结果）
                    log.warn("报告节[{}]第 {} 次流式输出截断({})，重试", sectionName, attempt, finishReason.get());
                    continue;
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

    /**
     * 节 2 分片回放：预生成完整文本按 ~30 字符/片推送 CONTENT（打字机观感），
     * 30ms 间隔——观感接近真实流式，且因内容已完整生成可安全重试。
     */
    private void pushInChunks(String markdown, SseSink sink) {
        int chunkSize = 30;
        for (int i = 0; i < markdown.length(); i += chunkSize) {
            int end = Math.min(i + chunkSize, markdown.length());
            sink.sendEvent(SseEvents.CONTENT, markdown.substring(i, end));
            try {
                Thread.sleep(30);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    /**
     * LLM 流式生成单节内容：每个 delta 立即推送 SSE CONTENT 事件（打字机效果），
     * 同时累积完整文本返回；空白响应重试一次（官网偶发空流），失败返回占位文本。
     */
    private String streamSection(String query, String resourcesText, String sectionPrompt,
                                 String sectionName, SseSink sink, String styleHint,
                                 String sessionSummary, boolean degraded, DraftContext draft) {
        ChatOptions options = ChatOptions.builder().maxTokens(MAX_TOKENS_PER_SECTION).build();
        String prompt = buildSectionPrompt(query, resourcesText, sectionPrompt,
                styleHint, sessionSummary, degraded);
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
                            sink.sendEvent(SseEvents.CONTENT, delta);
                            if (draft != null) {
                                draft.appendAndMaybeNotify(delta);
                            }
                        })
                        .timeout(streamTimeout)
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

    /**
     * DeepSeek（OpenAI 兼容）内容截断标记：达 max_tokens / 服务端资源不足中断。
     * spring-ai-deepseek 将 finish_reason 映射为枚举 name()（大写 "LENGTH"），
     * 故比较必须忽略大小写（曾有大小写不匹配导致检测失效的教训）。
     */
    private static boolean isTruncated(String finishReason) {
        return finishReason != null
                && (finishReason.equalsIgnoreCase("length")
                    || finishReason.equalsIgnoreCase("insufficient_system_resource"));
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

    /**
     * prompt 注入防护：< > 转义（与 preProcessQuery/regenerateReport 一致），
     * 防止用户输入/会话摘要/网页内容闭合 XML 标签注入指令。
     */
    private static String escapeXml(String s) {
        return s == null ? "" : s.replace("<", "&lt;").replace(">", "&gt;");
    }

    private String buildSectionPrompt(String query, String resourcesText,
                                      String sectionPrompt, String styleHint, String sessionSummary,
                                      boolean degraded) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一个技术教育专家，正在撰写关于「").append(escapeXml(query))
                .append("」的深度学习报告。基于以下搜索结果素材撰写。\n\n");
        String style = styleHint == null ? null : INTENT_STYLE.get(styleHint);
        if (style != null) {
            sb.append(style).append("\n\n");
        }
        if (degraded) {
            sb.append("【材料受限】素材为搜索摘要级内容，信息有限：本节约 250-400 字，"
                    + "如实总结素材要点，信息不足处标注 [信息不足，建议访问原文]，"
                    + "严禁虚构素材之外的信息。\n\n");
        }
        // 会话上下文（长记忆）：延续前文叙述、避免重复已讲内容；
        // 安全边界——摘要仅作上下文参考，引用来源必须来自本次素材
        if (sessionSummary != null && !sessionSummary.isBlank()) {
            sb.append("会话上下文（此前对话的滚动摘要，仅作延续叙述参考，"
                    + "不得作为引用来源，引用必须来自本次搜索素材，不得虚构来源）：\n")
                    .append("<session_memory>\n").append(escapeXml(sessionSummary))
                    .append("\n</session_memory>\n\n");
        }
        sb.append(sectionPrompt).append("\n\n---\n\n").append(escapeXml(resourcesText));
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

}
