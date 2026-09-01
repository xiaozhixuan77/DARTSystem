package com.dart.service;

import com.dart.agent.IntentKeys;
import com.dart.util.VectorUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 前置查询处理：领域判断、意图分类、关键词优化（单次 LLM 调用）与词表锚点兜底。
 */
@Service
public class QueryPreProcessor {

    private static final Logger log = LoggerFactory.getLogger(QueryPreProcessor.class);

    private final ChatModel chatModel;
    private final EmbeddingService embeddingService;

    /** 变体词主题校验阈值：与主词 embedding 余弦低于此值视为跑题，搜索前丢弃 */
    @Value("${agent.search.variant-similarity-threshold:0.5}")
    private double variantSimilarityThreshold = 0.5;

    /**
     * 抽象概念词 / 高频歧义词 → 主流技术锚点映射（服务端兜底）。
     * LLM 有时不遵守 prompt 的锚点指令（实测"缓存"未带 Redis），
     * 检测到抽象词且结果中无对应锚点时自动追加，防 Bing 命中无关内容。
     */
    private static final Map<String, List<String>> TECH_ANCHOR_MAP = Map.ofEntries(
            Map.entry("缓存", List.of("Redis", "Memcached")),
            Map.entry("云原生", List.of("Kubernetes", "Docker")),
            Map.entry("容器", List.of("Docker", "Kubernetes")),
            Map.entry("微服务", List.of("Spring Cloud", "gRPC")),
            Map.entry("网关", List.of("Spring Cloud Gateway", "Kong")),
            Map.entry("消息队列", List.of("Kafka", "RabbitMQ")),
            Map.entry("数据库", List.of("MySQL", "PostgreSQL")),
            Map.entry("搜索引擎", List.of("Elasticsearch")),
            Map.entry("前端", List.of("Vue", "React")),
            Map.entry("大模型", List.of("ChatGPT", "Claude")),
            Map.entry("三级缓存", List.of("Spring", "循环依赖")),
            Map.entry("AOP", List.of("Spring AOP")),
            Map.entry("自动配置", List.of("Spring Boot")));

    /**
     * 歧义中文技术词 → 稳定英文锚点（LLM optimizedEn 缺失时的词表降级）。
     * 值必须实测稳定：Bing 对 "microservices" 词系随机污染（索引故障，cn/www 双域均返回无关
     * 内容），"spring cloud" 等词系稳定（spring boot/docker/python 实测正常）。
     */
    private static final Map<String, String> EN_ANCHOR_MAP = Map.ofEntries(
            Map.entry("微服务", "spring cloud"), Map.entry("云原生", "cloud native"),
            Map.entry("容器", "container"), Map.entry("网关", "api gateway"),
            Map.entry("消息队列", "message queue"), Map.entry("缓存", "cache"),
            Map.entry("数据库", "database"), Map.entry("搜索引擎", "search engine"),
            Map.entry("前端", "frontend"), Map.entry("大模型", "large language model"),
            Map.entry("三级缓存", "spring three-level cache"), Map.entry("AOP", "spring aop"),
            Map.entry("自动配置", "spring boot autoconfiguration"),
            Map.entry("分布式", "distributed system"), Map.entry("高并发", "high concurrency"),
            Map.entry("并发", "concurrency"),
            Map.entry("部署", "deployment"), Map.entry("集群", "cluster"));

    private static final Pattern HAN_PATTERN = Pattern.compile("\\p{IsHan}");
    private static final Pattern EN_TOKEN_PATTERN =
            Pattern.compile("[a-zA-Z][a-zA-Z0-9.]{1,}");
    private static final Pattern FOLLOWUP_PATTERN =
            Pattern.compile("\"isFollowUp\"\\s*:\\s*(true|false)");
    private static final Pattern IS_TECH_PATTERN =
            Pattern.compile("\"isTech\"\\s*:\\s*(true|false)");
    private static final Pattern OPTIMIZED_PATTERN =
            Pattern.compile("\"optimized\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern OPTIMIZED_EN_PATTERN =
            Pattern.compile("\"optimizedEn\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern INTENT_PATTERN =
            Pattern.compile("\"intent\"\\s*:\\s*\"([^\"]*)\"");

    public QueryPreProcessor(ChatModel chatModel, EmbeddingService embeddingService) {
        this.chatModel = chatModel;
        this.embeddingService = embeddingService;
    }

    public ChatService.QueryPreProcess preProcessQuery(String query) {
        return preProcessQuery(query, "");
    }

    /**
     * 合并领域判断、意图分类与提示词优化为一次 LLM 调用（JSON 输出 {isTech, optimized, variants, intent, isFollowUp}）。
     * 注入会话摘要后 LLM 可解析指代（"接着讲讲它"→具体 query）并判定续答意图。
     * 失败/解析失败降级：isTech=true（放行）、optimized=原查询、variants=空列表、intent=how_to、followUp=false。
     */
    public ChatService.QueryPreProcess preProcessQuery(String query, String summary) {
        try {
            String sanitized = query.replace("<", "&lt;").replace(">", "&gt;");
            String sanitizedSummary = (summary == null ? "" : summary).replace("<", "&lt;").replace(">", "&gt;");
            String prompt = buildPreProcessPrompt(sanitized, sanitizedSummary);
            String response = chatModel.call(new Prompt(new UserMessage(prompt)))
                    .getResults().getFirst().getOutput().getText().trim();
            boolean isTech = extractBoolean(response, IS_TECH_PATTERN, true);
            boolean followUp = isTech && extractBoolean(response, FOLLOWUP_PATTERN, false);
            String optimized = extractGroup(response, OPTIMIZED_PATTERN, null);
            List<String> variants = parseVariants(response);
            // 非技术查询意图强制归一化为 non_tech（以 isTech 判断为准）
            String intent = isTech ? extractGroup(response, INTENT_PATTERN, IntentKeys.HOW_TO) : IntentKeys.NON_TECH;
            // 服务端锚点兜底：LLM 未遵守锚点指令时（抽象词/歧义词无具体技术名），自动补充主流锚点
            String finalOptimized = (optimized == null || optimized.isBlank()) ? query : optimized;
            String optimizedEn = extractGroup(response, OPTIMIZED_EN_PATTERN, null);
            if (isTech && !followUp) {
                finalOptimized = applyTechAnchors(finalOptimized);
                // optimizedEn 违规含中文（中英混合实测仍被 Bing 污染）→ 丢弃，走词表降级
                if (optimizedEn != null && HAN_PATTERN.matcher(optimizedEn).find()) {
                    log.warn("optimizedEn 含中文字符（违规中英混合），丢弃改用词表降级: {}", optimizedEn);
                    optimizedEn = null;
                }
            }
            return new ChatService.QueryPreProcess(isTech, finalOptimized, variants, intent, followUp, optimizedEn);
        } catch (Exception e) {
            log.warn("LLM 前置判断与提示词优化失败，降级为技术查询+原文", e);
            return new ChatService.QueryPreProcess(true, query, List.of(), IntentKeys.HOW_TO, false, null);
        }
    }

    /**
     * 变体词主题校验：与主词 embedding 余弦相似度低于阈值视为跑题，搜索前丢弃。
     * fail-open：embedding 失败/返回 null 时保留全部变体词（校验是增强，失败不砍搜索）。
     */
    public List<String> filterVariants(String mainQuery, List<String> variants) {
        if (variants == null || variants.isEmpty()) {
            return List.of();
        }
        float[] mainVec;
        try {
            mainVec = embeddingService.embed(mainQuery);
        } catch (Exception e) {
            log.warn("主词向量化失败，跳过变体词校验", e);
            return variants;
        }
        if (mainVec == null) {
            return variants;
        }
        List<String> kept = new ArrayList<>();
        for (String variant : variants) {
            try {
                float[] vec = embeddingService.embed(variant);
                if (vec == null || VectorUtils.cosineSimilarity(mainVec, vec) >= variantSimilarityThreshold) {
                    kept.add(variant);
                } else {
                    log.debug("变体词与主词主题不一致，丢弃: variant={}", variant);
                }
            } catch (Exception e) {
                log.warn("变体词向量化失败，保留: variant={}", variant);
                kept.add(variant);
            }
        }
        return kept;
    }

    /**
     * 词表降级英文检索词生成：主词英文 token 组合优先，纯中文查询用歧义词稳定映射兜底。
     * 英文 token（Spring Cloud Kubernetes 等）实测 Bing 稳定且精确；歧义词映射词如
     * "distributed system" 会触发词典结果霸榜（M 评分低质混入）、"microservices" 词系随机
     * 污染，token 存在时一律不混入。无任何输出返回 null（由 LLM optimizedEn 翻译兜底）。
     */
    static String buildEnglishQuery(String query) {
        if (query == null || query.isBlank()) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        Matcher en = EN_TOKEN_PATTERN.matcher(query);
        while (en.find()) {
            // 转小写统一（避免 "Spring" vs "spring" 大小写不同导致重复/不命中）
            String tok = en.group().toLowerCase();
            if (!parts.contains(tok)) {
                parts.add(tok);
            }
        }
        if (!parts.isEmpty()) {
            return String.join(" ", parts);
        }
        List<Map.Entry<String, String>> byLengthDesc = EN_ANCHOR_MAP.entrySet().stream()
                .sorted(java.util.Comparator.comparingInt(e -> -e.getKey().length()))
                .toList();
        List<String> matchedKeys = new ArrayList<>();
        for (Map.Entry<String, String> entry : byLengthDesc) {
            if (!query.contains(entry.getKey())) {
                continue;
            }
            boolean covered = matchedKeys.stream().anyMatch(k -> k.contains(entry.getKey()));
            if (covered) {
                continue;
            }
            matchedKeys.add(entry.getKey());
            for (String word : entry.getValue().split("\\s+")) {
                if (!parts.contains(word)) {
                    parts.add(word);
                }
            }
        }
        return parts.isEmpty() ? null : String.join(" ", parts);
    }

    static String applyTechAnchors(String optimized) {
        if (optimized == null || optimized.isBlank()) {
            return optimized;
        }
        StringBuilder sb = new StringBuilder(optimized);
        // 长键优先匹配：已匹配长键（如"三级缓存"）时跳过其子串短键（如"缓存"），防锚点堆叠污染
        List<Map.Entry<String, List<String>>> byLengthDesc = TECH_ANCHOR_MAP.entrySet().stream()
                .sorted(java.util.Comparator.comparingInt(e -> -e.getKey().length()))
                .toList();
        List<String> matchedKeys = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : byLengthDesc) {
            if (!optimized.contains(entry.getKey())) {
                continue;
            }
            boolean coveredByLongerKey = matchedKeys.stream().anyMatch(k -> k.contains(entry.getKey()));
            if (coveredByLongerKey) {
                continue;
            }
            matchedKeys.add(entry.getKey());
            boolean hasAnyAnchor = entry.getValue().stream()
                    .anyMatch(optimized::contains);
            if (!hasAnyAnchor) {
                for (String anchor : entry.getValue()) {
                    sb.append(' ').append(anchor);
                }
            }
        }
        return sb.toString();
    }

    /**
     * 意图 → 搜索结果量：quick_fact 收窄（简洁回答）、deep_dive/comparison 扩大（召回更全）、其余默认。
     */
    static int targetResultsFor(String intent) {
        return switch (intent == null ? "" : intent) {
            case IntentKeys.QUICK_FACT -> 5;
            case IntentKeys.DEEP_DIVE, IntentKeys.COMPARISON -> 12;
            default -> 8;
        };
    }

    /** prompt 注入防护：< > 转义（与 CuratorTools 一致），防闭合 XML 标签注入指令。 */
    static String escapeXml(String s) {
        return s == null ? "" : s.replace("<", "&lt;").replace(">", "&gt;");
    }

    /** 单字段正则提取；字段缺失或畸形返回默认值，不抛异常。 */
    private static String extractGroup(String json, Pattern p, String def) {
        if (json == null || json.isBlank()) {
            return def;
        }
        Matcher m = p.matcher(json);
        return m.find() ? m.group(1) : def;
    }

    private static boolean extractBoolean(String json, Pattern p, boolean def) {
        if (json == null || json.isBlank()) {
            return def;
        }
        Matcher m = p.matcher(json);
        return m.find() ? Boolean.parseBoolean(m.group(1)) : def;
    }

    /**
     * 解析 variants 数组；字段缺失或畸形一律降级为空列表（只用主词搜索），不抛异常。
     */
    private static List<String> parseVariants(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        Matcher m = Pattern.compile("\"variants\"\\s*:\\s*\\[([^\\]]*)\\]").matcher(json);
        if (!m.find()) {
            return List.of();
        }
        List<String> variants = new ArrayList<>();
        Matcher item = Pattern.compile("\"([^\"]*)\"").matcher(m.group(1));
        while (item.find()) {
            String v = item.group(1).trim();
            if (!v.isBlank()) {
                variants.add(v);
            }
        }
        return variants;
    }

    private String buildPreProcessPrompt(String sanitized, String sanitizedSummary) {
        StringBuilder sb = new StringBuilder();
        sb.append("""
                你是一个技术领域判断、意图分类与查询优化助手。对用户输入完成以下任务：
                1. 判断是否属于技术/编程领域（isTech）
                2. 优化为适合网络搜索的简洁搜索关键词组合（optimized）：
                   - 输出 10-25 字的搜索关键词组合（如"AI Agent 知识库 架构 RAG 向量数据库 选型"），
                     不要输出完整问句——搜索引擎对长问句分词会崩坏（实测 40+ 字问句被 Bing 拆成单个词）
                   - 删除问句句式（有哪些/怎么/为什么）、修饰词与口语化表达，保留核心技术术语
                   - 输入是代词/省略/口语化表达时，结合历史会话摘要解析为具体的技术问题
                   - 术语有歧义时（如"三级缓存"可能指 CPU 缓存或 Spring 循环依赖），
                     以历史摘要「已讨论」字段的语境为准，不按字面默认理解
                   - 补充关键上下文（技术栈、版本、场景）；非技术输入输出空字符串
                   - 查询主题涉及"架构/框架/选型/对比/主流方案"时，检索词必须附带 2-3 个该领域主流系统名作锚点
                     （例：AI Agent 编排→LangGraph、AutoGen、CrewAI、OpenAI Agents；大模型→ChatGPT、Claude、Gemini；
                     Web 后端→Spring Boot、Node.js、FastAPI），仅写抽象概念词（如"主流AI Agent系统"）会让搜索只返回入门科普
                   - 抽象概念词（云原生、微服务、容器化、技术栈、架构、开发、实践、选型、对比、原理）单独作为
                     检索词会命中无关内容（实测"云原生 技术栈"返回云游戏站），必须与 2-3 个具体技术名词组合
                     （例：云原生→Kubernetes、Docker、Istio、Helm、Spring Cloud；微服务→Spring Cloud、gRPC、Service Mesh、
                     Nacos、Sentinel），严禁输出不含具体技术名词的检索词
                3. 生成 2 个检索变体词（variants）用于并行网络搜索扩大召回：
                   - 与 optimized 不同的表达角度/补充上下文（如英文术语、更短的关键词组合、场景化描述）
                   - 每个不超过 50 字；非技术输入输出空数组 []
                3.5 将 optimized 翻译为英文检索关键词组合（optimizedEn）：
                   - 忠实翻译中文技术概念为英文（微服务→microservices、云原生→cloud native、缓存→cache），
                     已含英文的技术名词（Spring Cloud、Kubernetes、Docker 等）保留原文
                   - 输出纯英文 10-25 字的检索关键词组合，严禁中英混合（实测中英混合检索词会被搜索引擎忽略）
                   - 非技术输入输出空字符串 ""
                4. 判断查询意图类型（intent）：
                   - quick_fact: 快速事实/定义类问题
                   - how_to: 操作步骤/配置方法
                   - deep_dive: 需要深入解释的概念/原理
                   - comparison: 对比两个及以上技术/工具
                   - troubleshooting: 错误诊断/排错
                   - non_tech: 非技术领域问题（与 isTech=false 对应）
                   - followup: 续答意图——输入指代会话前文（"它/上面/那 X 呢"）或要求基于上文解释（"为什么不行/展开说说"），
                             且脱离历史摘要无法理解。仅明确指代上文时才判定为续答（保守），正常独立查询不得判定。
                             续答意图时 optimized 输出空字符串、variants 输出空数组 []、isFollowUp 输出 true
                5. 判断是否续答意图（isFollowUp）：规则同 intent 的 followup 说明；
                   以下情况恒为 false：<session_memory> 段不存在（无历史记录）、输入是独立的新主题查询

                严格只输出 JSON，不要输出任何其他内容：
                {"isTech": true或false, "optimized": "优化后的描述", "optimizedEn": "英文检索词", "variants": ["变体1", "变体2"], "intent": "how_to", "isFollowUp": false}
                """);
        if (sanitizedSummary != null && !sanitizedSummary.isBlank()) {
            sb.append("\n历史会话摘要（仅作指代解析与续答判定参考）：\n<session_memory>\n")
                    .append(sanitizedSummary)
                    .append("\n</session_memory>\n");
        }
        sb.append("\n<user_query>").append(sanitized).append("</user_query>");
        return sb.toString();
    }
}
