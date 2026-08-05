package com.dart.service;

import com.dart.agent.curator.CuratedReport;
import com.dart.agent.curator.CuratorAgent;
import com.dart.agent.scoring.ScoredResource;
import com.dart.agent.search.SearchAgent;
import com.dart.entity.Message;
import com.dart.exception.ErrorCode;
import com.dart.mapper.MessageMapper;
import com.dart.util.RedisKeyConstants;
import com.dart.util.SseEvents;
import com.dart.util.SseUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * 核心编排服务：LLM 领域过滤 → RAG 检查 → Search Agent → Curator Agent → 追问生成 → 持久化。
 */
@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    private static final int FOLLOW_UP_MAX_REPORT_CHARS = 3000;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final ChatModel chatModel;
    private final SearchAgent searchAgent;
    private final CuratorAgent curatorAgent;
    private final RagService ragService;
    private final EmbeddingService embeddingService;
    private final MessageMapper messageMapper;
    private final StringRedisTemplate redisTemplate;

    @Value("${agent.cache.ttl-hours:24}")
    private long cacheTtlHours;

    public ChatService(ChatModel chatModel, SearchAgent searchAgent, CuratorAgent curatorAgent,
                       RagService ragService, EmbeddingService embeddingService,
                       MessageMapper messageMapper, StringRedisTemplate redisTemplate) {
        this.chatModel = chatModel;
        this.searchAgent = searchAgent;
        this.curatorAgent = curatorAgent;
        this.ragService = ragService;
        this.embeddingService = embeddingService;
        this.messageMapper = messageMapper;
        this.redisTemplate = redisTemplate;
    }

    public record SearchResult(RagService.RagResult ragAnswer,
                               CuratedReport curatedReport, List<ScoredResource> searchResults) {
        public boolean isRagHit() { return ragAnswer != null; }
        public static SearchResult ragHit(RagService.RagResult answer) {
            return new SearchResult(answer, null, List.of());
        }
        public static SearchResult searchResult(CuratedReport report, List<ScoredResource> resources) {
            return new SearchResult(null, report, resources);
        }
    }

    public record QueryAnalysis(boolean isTech) {}

    /**
     * 执行完整的搜索-整理流程（供 SSE 端点调用）。
     */
    @Transactional
    public SearchResult executeSearchAndCurate(String query, Long userId,
                                                Long conversationId, SseEmitter emitter) {
        // 1. 持久化用户消息
        Message userMsg = new Message();
        userMsg.setConversationId(conversationId);
        userMsg.setRole("user");
        userMsg.setContent(query);
        userMsg.setCreatedAt(LocalDateTime.now());
        messageMapper.insert(userMsg);

        // 1.5 查询缓存检查（原始查询规范化 key；命中直接返回，跳过全流程）
        SearchResult cached = tryCache(query, conversationId, emitter);
        if (cached != null) {
            return cached;
        }

        // 2. 领域过滤 + 提示词优化（合并为一次 LLM 调用）
        QueryPreProcess pre = preProcessQuery(query);
        if (!pre.isTech()) {
            SseUtils.sendError(emitter, ErrorCode.NON_TECH_QUERY.getCode(),
                    ErrorCode.NON_TECH_QUERY.getMessage());
            SearchResult errorResult = SearchResult.searchResult(
                    errorReport(ErrorCode.NON_TECH_QUERY.getMessage()), List.of());
            persistAssistantMessage(conversationId, errorResult);
            return errorResult;
        }

        // 优化后的查询用于 RAG/搜索/整理；用户消息持久化仍存原文
        String effectiveQuery = pre.optimized();

        // 3+4. RAG 检查与 Search Agent 并行启动；RAG 命中则短路，未命中等搜索完成。
        //     RAG 命中时不等慢搜索任务（shutdownNow 中断，虚拟线程后台收尾）
        SseUtils.sendStatus(emitter, "rag_check", "正在检索知识库...");
        List<ScoredResource> searchResults;
        ExecutorService parallelExecutor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            Future<Optional<RagService.RagResult>> ragFuture = parallelExecutor.submit(() ->
                    ragService.retrieve(effectiveQuery, userId));
            Future<List<ScoredResource>> searchFuture = parallelExecutor.submit(() ->
                    searchAgent.execute(effectiveQuery, 8, emitter));

            Optional<RagService.RagResult> ragResult;
            try {
                ragResult = ragFuture.get();
            } catch (Exception e) {
                log.warn("RAG 检索异常，继续搜索流程", e);
                ragResult = Optional.empty();
            }
            if (ragResult.isPresent()) {
                SearchResult result = SearchResult.ragHit(ragResult.get());
                SseUtils.sendEvent(emitter, SseEvents.CONTENT, result.ragAnswer().answer());
                persistAssistantMessage(conversationId, result);
                return result;
            }

            try {
                searchResults = searchFuture.get();
            } catch (Exception e) {
                log.error("Search Agent 异常", e);
                SseUtils.sendError(emitter, ErrorCode.AGENT_EXECUTION_ERROR.getCode(),
                        "搜索服务暂时不可用，请稍后重试");
                SearchResult errorResult = SearchResult.searchResult(
                        errorReport("搜索服务暂时不可用，请稍后重试"), List.of());
                persistAssistantMessage(conversationId, errorResult);
                return errorResult;
            }
        } finally {
            parallelExecutor.shutdownNow();
        }

        if (searchResults.isEmpty()) {
            SseUtils.sendError(emitter, ErrorCode.NO_QUALITY_RESULTS.getCode(),
                    ErrorCode.NO_QUALITY_RESULTS.getMessage());
            SearchResult emptyResult = SearchResult.searchResult(
                    new CuratedReport(effectiveQuery,
                            "# " + effectiveQuery + "\n\n未找到高质量资源，请尝试更换关键词。",
                            List.of(), Instant.now()),
                    List.of());
            persistAssistantMessage(conversationId, emptyResult);
            return emptyResult;
        }

        // 5. Curator Agent
        CuratedReport report;
        try {
            report = curatorAgent.curate(effectiveQuery, searchResults, emitter);
        } catch (Exception e) {
            log.error("Curator Agent 异常", e);
            report = buildFallbackReport(effectiveQuery, searchResults);
        }

        // 6. 报告先落库；追问后台生成后 SSE 追加并更新消息（失败不影响主流程）。
        //    主线程等待追问完成再返回，保证 done 发出时前端重拉的消息完整。
        SearchResult result = SearchResult.searchResult(report, searchResults);
        Message assistantMessage = persistAssistantMessage(conversationId, result);
        CuratedReport finalReport = report;
        Message finalMessage = assistantMessage;
        if (finalMessage != null) {
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                Future<?> followUpTask = executor.submit(() -> {
                    String followUp = generateFollowUp(effectiveQuery, finalReport.markdown());
                    if (followUp == null || followUp.isBlank()) {
                        return;
                    }
                    SseUtils.sendStatus(emitter, "curating", "正在生成追问建议...");
                    SseUtils.sendEvent(emitter, SseEvents.CONTENT,
                            "\n\n---\n\n## 进一步探索\n\n" + followUp);
                    CuratedReport withFollowUp = appendFollowUp(finalReport, followUp);
                    try {
                        finalMessage.setContent(withFollowUp.markdown());
                        messageMapper.updateById(finalMessage);
                    } catch (Exception e) {
                        log.error("更新追问消息失败", e);
                    }
                });
                followUpTask.get();
                // 缓存 key 必须与读取端一致（原始查询规范化哈希）
                writeCache(query, finalMessage.getContent(), report.sources());
            } catch (Exception e) {
                log.warn("追问生成任务异常", e);
            }
        }
        return result;
    }

    private CuratedReport buildFallbackReport(String query, List<ScoredResource> resources) {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(query).append("\n\n");
        sb.append("> AI 报告生成暂时不可用，以下为按质量评分排序的原始搜索结果。\n\n");
        int i = 1;
        for (ScoredResource r : resources) {
            sb.append("## ").append(i).append(". ").append(r.getTitle())
                    .append(" ⭐").append(String.format("%.1f", r.getWeightedScore())).append("\n\n");
            sb.append("- 来源: ").append(r.getUrl()).append("\n\n");
            i++;
        }
        return new CuratedReport(query, sb.toString(),
                resources.stream().map(ScoredResource::getUrl).toList(), Instant.now());
    }

    // ─── 追问生成 ────────────────────────────────────────────

    /**
     * 基于知识图谱报告内容，由 LLM 生成 2-3 条自然语言追问建议。
     * 失败时返回 null，不影响主流程。
     */
    private String generateFollowUp(String query, String reportMarkdown) {
        try {
            String truncated = reportMarkdown.length() > FOLLOW_UP_MAX_REPORT_CHARS
                    ? reportMarkdown.substring(0, FOLLOW_UP_MAX_REPORT_CHARS)
                    : reportMarkdown;
            String sanitized = query.replace("<", "&lt;").replace(">", "&gt;");
            String prompt = """
                    以下是一份知识学习报告（含 Mermaid 知识图谱和结构化章节），根据报告已覆盖的内容，提出2-3个读者可能感兴趣的追问方向。
                    追问应与知识图谱的分支主题相关但不重复已讲内容，以自然对话语气、中文写出。

                    用户查询：<user_query>%s</user_query>
                    报告摘要：
                    %s

                    严格按以下格式输出（每条追问以"- "开头，不要编号，不要加粗，不要空行）：
                    - 追问问题1
                    - 追问问题2
                    - 追问问题3
                    """.formatted(sanitized, truncated);
            String response = chatModel.call(new Prompt(new UserMessage(prompt)))
                    .getResults().getFirst().getOutput().getText().trim();
            return response.isBlank() ? null : response;
        } catch (Exception e) {
            log.warn("追问生成失败，跳过", e);
            return null;
        }
    }

    private CuratedReport appendFollowUp(CuratedReport report, String followUp) {
        String markdown = report.markdown() + "\n\n---\n\n## 进一步探索\n\n" + followUp;
        return new CuratedReport(report.title(), markdown, report.sources(), report.generatedAt());
    }

    // ─── 持久化 ──────────────────────────────────────────────

    private Message persistAssistantMessage(Long conversationId, SearchResult result) {
        try {
            Message assistantMsg = new Message();
            assistantMsg.setConversationId(conversationId);
            assistantMsg.setRole("assistant");
            if (result.isRagHit()) {
                assistantMsg.setContent(result.ragAnswer().answer());
                assistantMsg.setMetadata("{\"type\":\"rag\"," +
                        "\"sources\":" + toJsonArray(result.ragAnswer().sourceUrls()) + "}");
            } else if (result.curatedReport() != null) {
                assistantMsg.setContent(result.curatedReport().markdown());
                assistantMsg.setMetadata("{\"type\":\"search\"," +
                        "\"sources\":" + toJsonArray(result.curatedReport().sources()) +
                        ",\"resultCount\":" + result.searchResults().size() + "}");
            }
            assistantMsg.setCreatedAt(LocalDateTime.now());
            messageMapper.insert(assistantMsg);
            return assistantMsg;
        } catch (Exception e) {
            log.error("保存助手消息失败", e);
            return null;
        }
    }

    private static String toJsonArray(List<String> urls) {
        if (urls == null || urls.isEmpty()) return "[]";
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < urls.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append("\"").append(urls.get(i).replace("\"", "\\\"")).append("\"");
        }
        sb.append("]");
        return sb.toString();
    }

    // ─── 报告查询缓存 ────────────────────────────────────────

    /**
     * 读取报告缓存：命中则 SSE 推送并持久化消息，返回结果；未命中返回 null。
     * 缓存 key 为原始查询（trim + 小写）的 SHA-256 前 8 字节十六进制。
     */
    private SearchResult tryCache(String query, Long conversationId, SseEmitter emitter) {
        try {
            String json = redisTemplate.opsForValue().get(cacheKey(query));
            if (json == null || json.isBlank()) {
                return null;
            }
            JsonNode node = OBJECT_MAPPER.readTree(json);
            String markdown = node.path("markdown").asText();
            if (markdown.isBlank()) {
                return null;
            }
            List<String> sources = new ArrayList<>();
            node.path("sources").forEach(s -> sources.add(s.asText()));
            CuratedReport report = new CuratedReport(query, markdown, sources, Instant.now());
            SearchResult result = SearchResult.searchResult(report, List.of());
            SseUtils.sendEvent(emitter, SseEvents.CONTENT, markdown);
            persistAssistantMessage(conversationId, result);
            log.info("报告缓存命中: query={}", query);
            return result;
        } catch (Exception e) {
            log.warn("读取报告缓存失败，继续完整流程", e);
            return null;
        }
    }

    /**
     * 写入报告缓存（仅搜索路径；RAG 回答与用户知识库相关，不缓存）。
     */
    private void writeCache(String query, String markdown, List<String> sources) {
        try {
            Map<String, Object> data = Map.of("markdown", markdown, "sources", sources);
            redisTemplate.opsForValue().set(cacheKey(query),
                    OBJECT_MAPPER.writeValueAsString(data), Duration.ofHours(cacheTtlHours));
        } catch (Exception e) {
            log.warn("写入报告缓存失败", e);
        }
    }

    private String cacheKey(String query) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(query.trim().toLowerCase().getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(RedisKeyConstants.REPORT_CACHE_PREFIX);
            for (int i = 0; i < 8; i++) {
                sb.append(String.format("%02x", hash[i]));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    // ─── 前置判断与提示词优化（合并为一次调用）────────────────────────

    public record QueryPreProcess(boolean isTech, String optimized) {}

    /**
     * 合并领域判断与提示词优化为一次 LLM 调用（JSON 输出 {isTech, optimized}）。
     * 失败/解析失败降级：isTech=true（放行）、optimized=原查询。用户输入用 XML 标签包裹防注入。
     */
    public QueryPreProcess preProcessQuery(String query) {
        try {
            String sanitized = query.replace("<", "&lt;").replace(">", "&gt;");
            String prompt = """
                    你是一个技术领域判断与查询优化助手。对用户输入完成两件事：
                    1. 判断是否属于技术/编程领域（isTech）
                    2. 优化为更适合知识库检索和网络搜索的技术问题描述（optimized）：
                       - 保持原始意图，补充关键上下文（技术栈、版本、场景）
                       - 去除口语化表达，使用准确的技术术语
                       - 输入已清晰准确时只做小幅润色
                       - 控制在 100 字以内；非技术输入输出空字符串

                    严格只输出 JSON，不要输出任何其他内容：
                    {"isTech": true或false, "optimized": "优化后的描述"}

                    <user_query>%s</user_query>
                    """.formatted(sanitized);
            String response = chatModel.call(new Prompt(new UserMessage(prompt)))
                    .getResults().getFirst().getOutput().getText().trim();
            boolean isTech = parseIsTech(response);
            String optimized = parseOptimized(response);
            return new QueryPreProcess(isTech,
                    (optimized == null || optimized.isBlank()) ? query : optimized);
        } catch (Exception e) {
            log.warn("LLM 前置判断与提示词优化失败，降级为技术查询+原文", e);
            return new QueryPreProcess(true, query);
        }
    }

    private static boolean parseIsTech(String json) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"isTech\"\\s*:\\s*(true|false)").matcher(json);
        if (m.find()) {
            return Boolean.parseBoolean(m.group(1));
        }
        return true; // 解析失败默认放行
    }

    private static String parseOptimized(String json) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"optimized\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
        return m.find() ? m.group(1) : null;
    }

    // ─── 工具方法 ────────────────────────────────────────────

    private CuratedReport errorReport(String message) {
        return new CuratedReport("搜索错误", "# 搜索错误\n\n" + message, List.of(), Instant.now());
    }
}
