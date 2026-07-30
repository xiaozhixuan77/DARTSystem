package com.dart.service;

import com.dart.agent.curator.CuratedReport;
import com.dart.agent.curator.CuratorAgent;
import com.dart.agent.scoring.ScoredResource;
import com.dart.agent.search.SearchAgent;
import com.dart.entity.Message;
import com.dart.exception.ErrorCode;
import com.dart.mapper.MessageMapper;
import com.dart.util.SseEvents;
import com.dart.util.SseUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 核心编排服务：LLM 领域过滤 → RAG 检查 → Search Agent → Curator Agent → 持久化。
 */
@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    private final ChatModel chatModel;
    private final SearchAgent searchAgent;
    private final CuratorAgent curatorAgent;
    private final RagService ragService;
    private final EmbeddingService embeddingService;
    private final MessageMapper messageMapper;

    public ChatService(ChatModel chatModel, SearchAgent searchAgent, CuratorAgent curatorAgent,
                       RagService ragService, EmbeddingService embeddingService,
                       MessageMapper messageMapper) {
        this.chatModel = chatModel;
        this.searchAgent = searchAgent;
        this.curatorAgent = curatorAgent;
        this.ragService = ragService;
        this.embeddingService = embeddingService;
        this.messageMapper = messageMapper;
    }

    public record SearchResult(RagService.RagResult ragAnswer,
                               CuratedReport curatedReport, List<ScoredResource> searchResults,
                               ClarificationData clarification) {
        public boolean isRagHit() { return ragAnswer != null; }
        public boolean isClarification() { return clarification != null; }
        public static SearchResult ragHit(RagService.RagResult answer) {
            return new SearchResult(answer, null, List.of(), null);
        }
        public static SearchResult searchResult(CuratedReport report, List<ScoredResource> resources) {
            return new SearchResult(null, report, resources, null);
        }
        public static SearchResult clarification(ClarificationData data) {
            return new SearchResult(null, null, List.of(), data);
        }
    }

    public record QueryAnalysis(boolean isTech, boolean isVague,
                                String detectedTopic, List<String> clarifyingQuestions) {}

    public record ClarificationData(String content, String metadataJson) {}

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

        // 2. 查询分析：领域判断 + 模糊度检测
        QueryAnalysis analysis = analyzeQuery(query);
        if (!analysis.isTech()) {
            SseUtils.sendError(emitter, ErrorCode.NON_TECH_QUERY.getCode(),
                    ErrorCode.NON_TECH_QUERY.getMessage());
            SearchResult errorResult = SearchResult.searchResult(
                    errorReport(ErrorCode.NON_TECH_QUERY.getMessage()), List.of());
            persistAssistantMessage(conversationId, errorResult);
            return errorResult;
        }

        if (analysis.isVague()) {
            SseUtils.sendStatus(emitter, "clarifying", "正在分析需求...");
            ClarificationData clarification = buildClarification(analysis);
            persistClarificationMessage(conversationId, clarification);
            return SearchResult.clarification(clarification);
        }

        // 3. RAG 检查
        SseUtils.sendStatus(emitter, "rag_check", "正在检查知识库...");
        Optional<RagService.RagResult> ragResult = ragService.retrieve(query, userId);
        if (ragResult.isPresent()) {
            SearchResult result = SearchResult.ragHit(ragResult.get());
            persistAssistantMessage(conversationId, result);
            return result;
        }

        // 4. Search Agent
        List<ScoredResource> searchResults;
        try {
            searchResults = searchAgent.execute(query, 8, emitter);
        } catch (Exception e) {
            log.error("Search Agent 异常", e);
            SseUtils.sendError(emitter, ErrorCode.AGENT_EXECUTION_ERROR.getCode(),
                    "搜索服务暂时不可用，请稍后重试");
            SearchResult errorResult = SearchResult.searchResult(
                    errorReport("搜索服务暂时不可用，请稍后重试"), List.of());
            persistAssistantMessage(conversationId, errorResult);
            return errorResult;
        }

        if (searchResults.isEmpty()) {
            SseUtils.sendError(emitter, ErrorCode.NO_QUALITY_RESULTS.getCode(),
                    ErrorCode.NO_QUALITY_RESULTS.getMessage());
            SearchResult emptyResult = SearchResult.searchResult(
                    new CuratedReport(query,
                            "# " + query + "\n\n未找到高质量资源，请尝试更换关键词。",
                            List.of(), Instant.now()),
                    List.of());
            persistAssistantMessage(conversationId, emptyResult);
            return emptyResult;
        }

        // 5. Curator Agent
        CuratedReport report;
        try {
            report = curatorAgent.curate(query, searchResults, emitter);
        } catch (Exception e) {
            log.error("Curator Agent 异常", e);
            report = new CuratedReport(query,
                    "# " + query + "\n\n> AI 报告生成暂时不可用，以下为按质量评分排序的原始搜索结果。\n\n"
                            + buildSimpleResourceList(searchResults),
                    searchResults.stream().map(ScoredResource::getUrl).toList(),
                    Instant.now());
        }

        SearchResult result = SearchResult.searchResult(report, searchResults);
        persistAssistantMessage(conversationId, result);
        return result;
    }

    private void persistAssistantMessage(Long conversationId, SearchResult result) {
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
        } catch (Exception e) {
            log.error("保存助手消息失败", e);
        }
    }

    private void persistClarificationMessage(Long conversationId, ClarificationData clarification) {
        try {
            Message msg = new Message();
            msg.setConversationId(conversationId);
            msg.setRole("assistant");
            msg.setContent(clarification.content());
            msg.setMetadata(clarification.metadataJson());
            msg.setCreatedAt(LocalDateTime.now());
            messageMapper.insert(msg);
        } catch (Exception e) {
            log.error("保存引导消息失败", e);
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

    /**
     * LLM 查询分析：一次性判断是否技术领域 + 是否模糊 + 生成引导问题。
     * 用户输入用 XML 标签包裹防止 prompt 注入。
     */
    public QueryAnalysis analyzeQuery(String query) {
        try {
            String sanitized = query.replace("<", "&lt;").replace(">", "&gt;");
            String prompt = """
                    分析用户查询，仅输出 JSON（不要其他文字）：
                    <user_query>%s</user_query>
                    规则：
                    - 非技术问题 → {"isTech":false}
                    - 清晰的技术问题 → {"isTech":true,"isVague":false}
                    - 模糊的技术查询（单关键词、过于宽泛、缺少上下文）→
                      {"isTech":true,"isVague":true,"detectedTopic":"主题","clarifyingQuestions":["引导1","引导2","引导3"]}
                    引导问题应帮助用户缩小范围，2-4个选项，每个不超过15字，使用中文。
                    """.formatted(sanitized);
            String response = chatModel.call(new Prompt(new UserMessage(prompt)))
                    .getResults().getFirst().getOutput().getText().trim();
            return parseQueryAnalysis(response);
        } catch (Exception e) {
            log.warn("查询分析失败，默认当作清晰技术查询处理", e);
            return new QueryAnalysis(true, false, null, List.of());
        }
    }

    private QueryAnalysis parseQueryAnalysis(String json) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper =
                    new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode node = mapper.readTree(json);
            boolean isTech = node.has("isTech") && node.get("isTech").asBoolean();
            boolean isVague = node.has("isVague") && node.get("isVague").asBoolean();
            String topic = node.has("detectedTopic") ? node.get("detectedTopic").asText() : null;
            List<String> questions = new java.util.ArrayList<>();
            if (node.has("clarifyingQuestions")) {
                for (com.fasterxml.jackson.databind.JsonNode q : node.get("clarifyingQuestions")) {
                    questions.add(q.asText());
                }
            }
            return new QueryAnalysis(isTech, isVague, topic, questions);
        } catch (Exception e) {
            log.warn("解析查询分析 JSON 失败: {}", json, e);
            return new QueryAnalysis(true, false, null, List.of());
        }
    }

    private ClarificationData buildClarification(QueryAnalysis analysis) {
        StringBuilder content = new StringBuilder();
        content.append("你想了解 **").append(analysis.detectedTopic()).append("** 的哪个方面呢？");
        content.append("可以试试更具体地描述你的需求，比如：\n");
        for (String q : analysis.clarifyingQuestions()) {
            content.append("\n- ").append(q);
        }

        StringBuilder meta = new StringBuilder("{\"type\":\"clarification\",");
        meta.append("\"detectedTopic\":\"").append(escapeJson(analysis.detectedTopic())).append("\",");
        meta.append("\"clarifyingQuestions\":[");
        for (int i = 0; i < analysis.clarifyingQuestions().size(); i++) {
            if (i > 0) meta.append(",");
            meta.append("\"").append(escapeJson(analysis.clarifyingQuestions().get(i))).append("\"");
        }
        meta.append("]}");

        return new ClarificationData(content.toString(), meta.toString());
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** @deprecated 使用 {@link #analyzeQuery(String)} 替代 */
    public boolean isTechQuery(String query) {
        return analyzeQuery(query).isTech();
    }

    private CuratedReport errorReport(String message) {
        return new CuratedReport("搜索错误", "# 搜索错误\n\n" + message, List.of(), Instant.now());
    }

    private String buildSimpleResourceList(List<ScoredResource> resources) {
        StringBuilder sb = new StringBuilder();
        int i = 1;
        for (ScoredResource r : resources) {
            sb.append("## ").append(i).append(". ").append(r.getTitle())
                    .append(" ⭐").append(String.format("%.1f", r.getWeightedScore())).append("\n\n");
            sb.append("- 来源: ").append(r.getUrl()).append("\n\n");
            i++;
        }
        return sb.toString();
    }
}
