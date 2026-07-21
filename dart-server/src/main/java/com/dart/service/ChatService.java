package com.dart.service;

import com.dart.agent.curator.CuratedReport;
import com.dart.agent.curator.CuratorAgent;
import com.dart.agent.scoring.ScoredResource;
import com.dart.agent.search.SearchAgent;
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
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.List;
import java.util.Map;
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

    public record SearchResult(boolean ragHit, Optional<RagService.RagResult> ragAnswer,
                               Optional<CuratedReport> curatedReport, List<ScoredResource> searchResults) {
        public static SearchResult ragHit(RagService.RagResult answer) {
            return new SearchResult(true, Optional.of(answer), Optional.empty(), List.of());
        }
        public static SearchResult searchResult(CuratedReport report, List<ScoredResource> resources) {
            return new SearchResult(false, Optional.empty(), Optional.of(report), resources);
        }
    }

    /**
     * 执行完整的搜索-整理流程（供 SSE 端点调用）。
     */
    public SearchResult executeSearchAndCurate(String query, Long userId, SseEmitter emitter) {
        if (!isTechQuery(query)) {
            SseUtils.sendError(emitter, ErrorCode.NON_TECH_QUERY.getCode(),
                    ErrorCode.NON_TECH_QUERY.getMessage());
            return SearchResult.searchResult(errorReport(ErrorCode.NON_TECH_QUERY.getMessage()), List.of());
        }

        SseUtils.sendStatus(emitter, "rag_check", "正在检查知识库...");
        Optional<RagService.RagResult> ragResult = ragService.retrieve(query, userId);
        if (ragResult.isPresent()) {
            SseUtils.sendEvent(emitter, SseEvents.CONTENT, ragResult.get().answer());
            SseUtils.sendEvent(emitter, SseEvents.DONE, Map.of("status", "complete"));
            return SearchResult.ragHit(ragResult.get());
        }

        List<ScoredResource> searchResults;
        try {
            searchResults = searchAgent.execute(query, 8, emitter);
        } catch (Exception e) {
            log.error("Search Agent 异常", e);
            SseUtils.sendError(emitter, ErrorCode.AGENT_EXECUTION_ERROR.getCode(), "搜索服务暂时不可用，请稍后重试");
            return SearchResult.searchResult(errorReport("搜索服务暂时不可用，请稍后重试"), List.of());
        }

        if (searchResults.isEmpty()) {
            SseUtils.sendError(emitter, ErrorCode.NO_QUALITY_RESULTS.getCode(),
                    ErrorCode.NO_QUALITY_RESULTS.getMessage());
            return SearchResult.searchResult(
                    new CuratedReport(query,
                            "# " + query + "\n\n未找到高质量资源，请尝试更换关键词。",
                            List.of(), Instant.now()),
                    List.of());
        }

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

        return SearchResult.searchResult(report, searchResults);
    }

    /**
     * LLM 前置判断是否为技术领域问题。用户输入用 XML 标签包裹防止 prompt 注入。
     */
    public boolean isTechQuery(String query) {
        try {
            String sanitized = query.replace("<", "&lt;").replace(">", "&gt;");
            String prompt = """
                    判断以下用户输入是否属于技术/编程领域。
                    仅输出 YES 或 NO：
                    <user_query>%s</user_query>
                    """.formatted(sanitized);
            String response = chatModel.call(new Prompt(new UserMessage(prompt)))
                    .getResults().getFirst().getOutput().getText().trim();
            return "YES".equalsIgnoreCase(response);
        } catch (Exception e) {
            log.warn("LLM 领域判断失败，默认为技术查询", e);
            return true;
        }
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
