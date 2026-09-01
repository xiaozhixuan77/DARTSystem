package com.dart.service;

import com.dart.broadcast.SseSink;
import com.dart.agent.IntentKeys;
import com.dart.agent.curator.CuratedReport;
import com.dart.agent.curator.CuratorAgent;
import com.dart.agent.scoring.ScoredResource;
import com.dart.agent.search.SearchAgent;
import com.dart.entity.Conversation;
import com.dart.entity.Message;
import com.dart.entity.SearchTrace;
import com.dart.exception.ErrorCode;
import com.dart.mapper.ConversationMapper;
import com.dart.mapper.MessageMapper;
import com.dart.mapper.SearchTraceMapper;
import com.dart.util.SseEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * 核心编排服务：LLM 领域过滤 → RAG 检查 → Search Agent → Curator Agent → 追问生成 → 持久化。
 * 前置解析/缓存/消息持久化/沉淀/记忆回答已拆至 QueryPreProcessor、ChatCacheService、
 * MessagePersistenceService、SedimentService、MemoryAnswerService。
 */
@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    /** 低质兜底报告（抓取失败、摘要级内容）的头部标注块 */
    private static final String DEGRADED_NOTICE =
            "> 说明：部分资源抓取失败，以下内容基于搜索结果摘要整理，内容深度有限，建议点击来源链接查看原文。";

    private final SearchAgent searchAgent;
    private final CuratorAgent curatorAgent;
    private final RagService ragService;
    private final ConversationSummaryService conversationSummaryService;
    private final MessageMapper messageMapper;
    private final SearchTraceMapper searchTraceMapper;
    private final ConversationMapper conversationMapper;
    private final TransactionTemplate transactionTemplate;
    private final QueryPreProcessor queryPreProcessor;
    private final ChatCacheService chatCacheService;
    private final MessagePersistenceService messagePersistenceService;
    private final SedimentService sedimentService;
    private final MemoryAnswerService memoryAnswerService;

    public ChatService(SearchAgent searchAgent, CuratorAgent curatorAgent, RagService ragService,
                       ConversationSummaryService conversationSummaryService,
                       MessageMapper messageMapper, SearchTraceMapper searchTraceMapper,
                       ConversationMapper conversationMapper, TransactionTemplate transactionTemplate,
                       QueryPreProcessor queryPreProcessor, ChatCacheService chatCacheService,
                       MessagePersistenceService messagePersistenceService,
                       SedimentService sedimentService, MemoryAnswerService memoryAnswerService) {
        this.searchAgent = searchAgent;
        this.curatorAgent = curatorAgent;
        this.ragService = ragService;
        this.conversationSummaryService = conversationSummaryService;
        this.messageMapper = messageMapper;
        this.searchTraceMapper = searchTraceMapper;
        this.conversationMapper = conversationMapper;
        this.transactionTemplate = transactionTemplate;
        this.queryPreProcessor = queryPreProcessor;
        this.chatCacheService = chatCacheService;
        this.messagePersistenceService = messagePersistenceService;
        this.sedimentService = sedimentService;
        this.memoryAnswerService = memoryAnswerService;
    }

    public record SearchResult(RagService.RagResult ragAnswer,
                               CuratedReport curatedReport, List<ScoredResource> searchResults,
                               String followUpAnswer) {
        public boolean isRagHit() { return ragAnswer != null; }
        public boolean isFollowUpHit() { return followUpAnswer != null; }
        public static SearchResult ragHit(RagService.RagResult answer) {
            return new SearchResult(answer, null, List.of(), null);
        }
        public static SearchResult searchResult(CuratedReport report, List<ScoredResource> resources) {
            return new SearchResult(null, report, resources, null);
        }
        public static SearchResult followUpAnswer(String answer) {
            return new SearchResult(null, null, List.of(), answer);
        }
    }

    public record QueryPreProcess(boolean isTech, String optimized, List<String> variants,
                                  String intent, boolean followUp, String optimizedEn) {}

    /**
     * 执行完整的搜索-整理流程（供 SSE 端点调用）。
     * 无外层事务：编排跨流式生成（1-2 分钟）不应长期占用连接池连接，
     * 各写库点（用户消息/草稿/报告/链路）经 REQUIRES_NEW 独立提交。
     */
    public SearchResult executeSearchAndCurate(String query, Long userId,
                                                Long conversationId, SseSink sink) {
        long startNanos = System.nanoTime();
        // 1. 持久化用户消息（独立事务立即提交：流式中断后刷新页面立即可见）
        Message userMsg = new Message();
        userMsg.setConversationId(conversationId);
        userMsg.setRole("user");
        userMsg.setContent(query);
        userMsg.setCreatedAt(LocalDateTime.now());
        transactionTemplate.executeWithoutResult(status -> {
            messageMapper.insert(userMsg);
            // 新会话标题 = 首条用户消息（截断规则与 ChatController 自动建会一致：
            // >50 字符截断；仅默认标题"新对话"时更新——用户自定义标题不被覆盖，
            // 条件更新避免先查后改竞态；"新对话"与 ConversationService 创建默认值/DB 默认值同源）
            String title = query.length() > 50 ? query.substring(0, 50) : query;
            conversationMapper.update(null,
                    new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<Conversation>()
                            .eq("id", conversationId)
                            .eq("title", "新对话")
                            .set("title", title));
        });

        // 1.5 查询缓存检查（原始查询规范化 key；命中直接返回，跳过全流程，不记链路）
        SearchResult cached = chatCacheService.tryCache(userId, query, conversationId, sink);
        if (cached != null) {
            return cached;
        }

        // 2. 领域过滤 + 提示词优化（合并为一次 LLM 调用）
        QueryPreProcess pre = queryPreProcessor.preProcessQuery(query);
        if (!pre.isTech()) {
            sink.sendError(ErrorCode.NON_TECH_QUERY.getCode(),
                    ErrorCode.NON_TECH_QUERY.getMessage());
            SearchResult errorResult = SearchResult.searchResult(
                    errorReport(ErrorCode.NON_TECH_QUERY.getMessage()), List.of());
            messagePersistenceService.persistAssistantMessage(conversationId, errorResult);
            recordTrace(userId, conversationId, userMsg.getId(), query, pre.intent(),
                    false, null, null, List.of(), "error", "non_tech_query", startNanos);
            return errorResult;
        }

        // 优化后的查询用于 RAG/搜索/整理；用户消息持久化仍存原文
        String effectiveQuery = pre.optimized();

        // 2.5 续答路由：LLM 判定为续答意图（指代上文/要求基于上文解释）→ 基于会话记忆回答，不搜索
        if (pre.followUp()) {
            Optional<String> answer = memoryAnswerService.generateMemoryAnswer(conversationId, userId, query);
            if (answer.isPresent()) {
                sink.sendStatus("curating", "正在基于会话记忆回答...");
                sink.sendEvent(SseEvents.CONTENT, answer.get());
                SearchResult result = SearchResult.followUpAnswer(answer.get());
                messagePersistenceService.persistAssistantMessage(conversationId, result);
                recordTrace(userId, conversationId, userMsg.getId(), query, IntentKeys.FOLLOWUP,
                        false, null, null, List.of(), "followup", null, startNanos);
                // 本轮问答也进入会话摘要（异步，不阻塞）
                conversationSummaryService.updateSummary(userId, conversationId, query, answer.get());
                log.info("续答路径命中，基于会话记忆回答: conversationId={}", conversationId);
                return result;
            }
            log.warn("续答回答生成失败，回退正常搜索流程: query={}", query);
            // 不 return，继续下方正常流程（fail-open）
        }

        // 3+4. RAG 检查与 Search Agent 并行启动；RAG 命中则短路，未命中等搜索完成。
        //     RAG 命中时不等慢搜索任务（shutdownNow 中断，虚拟线程后台收尾）
        sink.sendStatus("rag_check", "正在检索知识库...");
        List<ScoredResource> searchResults;
        boolean ragDegraded = false;
        ExecutorService parallelExecutor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            Future<Optional<RagService.RagResult>> ragFuture = parallelExecutor.submit(() ->
                    ragService.retrieve(effectiveQuery, userId));
            // 主词 + 英文检索词 + 变体词并行网络搜索扩大召回（RAG 仅用主词，保持检索专注）；
            // 英文检索词排第 2 位（补足优先级最高）：Bing 对含歧义中文词（如"微服务"）的查询
            // 会返回随机无关泛化结果。词表降级优先——词表产出的是验证稳定的短英文技术词
            // （Bing 对短词稳定，长句查询无论中英实测都易漂移污染）；LLM optimizedEn 兜底词表未覆盖词
            List<String> searchQueries = new ArrayList<>();
            searchQueries.add(effectiveQuery);
            String enQuery = QueryPreProcessor.buildEnglishQuery(effectiveQuery);
            if (enQuery == null) {
                enQuery = (pre.optimizedEn() != null && !pre.optimizedEn().isBlank())
                        ? pre.optimizedEn() : null;
            }
            if (enQuery != null && !enQuery.isBlank()) {
                searchQueries.add(enQuery);
            }
            // 变体词先经 embedding 主题校验，跑题词（如 LLM 联想跑偏）在搜索前丢弃
            searchQueries.addAll(queryPreProcessor.filterVariants(effectiveQuery, pre.variants()));
            Future<List<ScoredResource>> searchFuture = parallelExecutor.submit(() ->
                    searchAgent.execute(searchQueries, QueryPreProcessor.targetResultsFor(pre.intent()), sink));

            Optional<RagService.RagResult> ragResult;
            try {
                ragResult = ragFuture.get();
            } catch (Exception e) {
                log.warn("RAG 检索异常，继续搜索流程", e);
                ragResult = Optional.empty();
            }
            if (ragResult.isPresent()
                    && !ragResult.get().answer().contains(RagService.RAG_INSUFFICIENT_MARKER)) {
                SearchResult result = SearchResult.ragHit(ragResult.get());
                sink.sendEvent(SseEvents.CONTENT, result.ragAnswer().answer());
                messagePersistenceService.persistAssistantMessage(conversationId, result);
                recordTrace(userId, conversationId, userMsg.getId(), effectiveQuery, pre.intent(),
                        true, ragResult.get().maxSimilarity(), effectiveQuery,
                        List.of(), "rag", null, startNanos);
                // RAG 命中也有问答产生 → 更新会话摘要（异步，不阻塞）
                conversationSummaryService.updateSummary(userId, conversationId,
                        query, result.ragAnswer().answer());
                return result;
            }
            if (ragResult.isPresent()) {
                // RAG 命中但回答标记材料不足（弱相关擦线命中）→ 降级用并行搜索的结果，不短路
                ragDegraded = true;
                log.info("RAG 回答材料不足，降级搜索: query={}, maxSimilarity={}",
                        effectiveQuery, ragResult.get().maxSimilarity());
            }

            try {
                searchResults = searchFuture.get();
            } catch (Exception e) {
                log.error("Search Agent 异常", e);
                sink.sendError(ErrorCode.AGENT_EXECUTION_ERROR.getCode(),
                        "搜索服务暂时不可用，请稍后重试");
                SearchResult errorResult = SearchResult.searchResult(
                        errorReport("搜索服务暂时不可用，请稍后重试"), List.of());
                messagePersistenceService.persistAssistantMessage(conversationId, errorResult);
                recordTrace(userId, conversationId, userMsg.getId(), effectiveQuery, pre.intent(),
                        false, null, effectiveQuery, List.of(), "error", "search_exception", startNanos);
                return errorResult;
            }
        } finally {
            parallelExecutor.shutdownNow();
        }

        if (searchResults.isEmpty()) {
            sink.sendError(ErrorCode.NO_QUALITY_RESULTS.getCode(),
                    ErrorCode.NO_QUALITY_RESULTS.getMessage());
            SearchResult emptyResult = SearchResult.searchResult(
                    new CuratedReport(effectiveQuery,
                            "# " + effectiveQuery
                                    + "\n\n未找到高质量资源。建议补充具体技术名词（如 Kubernetes、Docker、"
                                    + "Spring Cloud、微服务框架）后重试。",
                            List.of(), Instant.now()),
                    List.of());
            messagePersistenceService.persistAssistantMessage(conversationId, emptyResult);
            recordTrace(userId, conversationId, userMsg.getId(), effectiveQuery, pre.intent(),
                    false, null, effectiveQuery, List.of(), "error", "search_empty", startNanos);
            return emptyResult;
        }

        // 5. Curator Agent（意图透传为报告风格要求；会话摘要注入延续前文叙述）
        // 先落草稿消息（独立事务立即提交，draft 标记）：流式生成期间前端可拉取
        // 已生成部分增量续流；完整报告生成后由 completeAssistantMessage 写回同一消息
        Message assistantDraft = messagePersistenceService.persistAssistantDraft(conversationId);
        CuratedReport report;
        // 低质兜底结果（抓取失败、摘要级内容）→ Curator 精简约束 + 报告头部标注
        boolean degraded = !searchResults.isEmpty() && searchResults.getFirst().isDegraded();
        try {
            // 摘要读取失败不降级报告生成（fail-open）
            String sessionSummary = null;
            try {
                sessionSummary = conversationSummaryService.getSummary(userId, conversationId);
            } catch (Exception e) {
                log.warn("会话摘要读取失败，报告生成不带历史上下文: conversationId={}", conversationId, e);
            }
            report = curatorAgent.curate(effectiveQuery, searchResults, sink,
                    pre.intent(), sessionSummary, degraded,
                    draft -> messagePersistenceService.updateAssistantDraft(
                            assistantDraft == null ? null : assistantDraft.getId(), draft));
            if (degraded) {
                report = new CuratedReport(report.title(),
                        DEGRADED_NOTICE + "\n\n" + report.markdown(),
                        report.sources(), report.generatedAt());
            }
        } catch (Exception e) {
            log.error("Curator Agent 异常", e);
            report = buildFallbackReport(effectiveQuery, searchResults);
        }

        // 6. 报告先落库；追问在事务提交后生成（afterCommit 更新消息，避免与原事务
        //    写锁冲突——Lock wait timeout），SSE 追加推送；done 自然延后到追问完成。
        SearchResult result = SearchResult.searchResult(report, searchResults);
        String fallbackReason = SedimentService.resolveFallbackReason(report.markdown());
        if (ragDegraded) {
            fallbackReason = "rag_insufficient" + (fallbackReason == null ? "" : "," + fallbackReason);
        }
        if (degraded) {
            // 前缀累加而非覆盖：保留 rag_insufficient 与节失败原因，链路可完整回溯降级链
            fallbackReason = "low_quality_fallback" + (fallbackReason == null ? "" : "," + fallbackReason);
        }
        recordTrace(userId, conversationId, userMsg.getId(), effectiveQuery, pre.intent(),
                false, null, effectiveQuery, searchResults, "search",
                fallbackReason, startNanos);
        Message assistantMessage = messagePersistenceService.completeAssistantMessage(
                assistantDraft, conversationId, result);
        // 报告经独立事务落库成功后，直接同步执行追问/缓存/沉淀（保持"落库成功才追问"语义）
        if (assistantMessage != null) {
            generateFollowUpAndUpdate(assistantMessage, report, query, sink,
                    userId, pre.intent(), degraded);
        }
        return result;
    }

    /**
     * 生成追问 → SSE 追加 → 更新消息；随后（无论追问成败）写入缓存并沉淀知识库。
     * 整体失败仅告警，不影响报告主流程。degraded（低质兜底）时跳过追问/缓存/沉淀：
     * 低质内容上生成追问=双重低质；低质报告污染知识库与 24h 缓存。
     */
    private void generateFollowUpAndUpdate(Message assistantMessage, CuratedReport report,
                                           String query, SseSink sink, Long userId,
                                           String intent, boolean degraded) {
        String markdown = report.markdown();
        try {
            if (!degraded) {
                String followUp = memoryAnswerService.generateFollowUp(query, markdown,
                        assistantMessage.getConversationId(), userId);
                if (followUp != null && !followUp.isBlank()) {
                    sink.sendStatus("curating", "正在生成追问建议...");
                    sink.sendEvent(SseEvents.CONTENT,
                            "\n\n---\n\n## 进一步探索\n\n" + followUp);
                    CuratedReport withFollowUp = memoryAnswerService.appendFollowUp(report, followUp);
                    assistantMessage.setContent(withFollowUp.markdown());
                    messageMapper.updateById(assistantMessage);
                    markdown = withFollowUp.markdown();
                }
            }
        } catch (Exception e) {
            log.warn("追问生成失败", e);
        } finally {
            if (!degraded) {
                chatCacheService.writeCache(userId, query, markdown, report.sources());
                sedimentService.sedimentReport(userId, report.title(), query, markdown,
                        report.sources(), intent);
            }
            // 摘要更新必须传原始报告（不含追问段），追问段会污染摘要
            if (assistantMessage.getConversationId() != null) {
                conversationSummaryService.updateSummary(userId,
                        assistantMessage.getConversationId(), query, report.markdown());
            }
        }
    }

    /**
     * 记录搜索链路追踪（与消息同事务写入；失败仅告警，不影响主流程）。
     * total_results/filtered_results 均记评分后数量（SearchAgent 已按评分截断；
     * 去重计数需 CuratorAgent 暴露，暂不区分）。
     */
    private void recordTrace(Long userId, Long conversationId, Long messageId, String query,
                             String intent, boolean ragHit, Double ragScore, String searchKeywords,
                             List<ScoredResource> results, String curationType,
                             String fallbackReason, long startNanos) {
        try {
            SearchTrace trace = new SearchTrace();
            trace.setUserId(userId);
            trace.setConversationId(conversationId);
            trace.setMessageId(messageId);
            trace.setQuery(query);
            trace.setIntent(intent);
            trace.setRagHit(ragHit ? 1 : 0);
            trace.setRagScore(ragScore);
            trace.setSearchKeywords(searchKeywords);
            int n = results == null ? 0 : results.size();
            trace.setTotalResults(n);
            trace.setFilteredResults(n);
            trace.setCurationType(curationType);
            trace.setFallbackReason(fallbackReason);
            trace.setProcessingTimeMs((int) ((System.nanoTime() - startNanos) / 1_000_000));
            trace.setCreatedAt(LocalDateTime.now());
            transactionTemplate.executeWithoutResult(status -> searchTraceMapper.insert(trace));
        } catch (Exception e) {
            log.warn("搜索链路追踪记录失败: query={}", query, e);
        }
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

    private CuratedReport errorReport(String message) {
        return new CuratedReport("搜索错误", "# 搜索错误\n\n" + message, List.of(), Instant.now());
    }
}
