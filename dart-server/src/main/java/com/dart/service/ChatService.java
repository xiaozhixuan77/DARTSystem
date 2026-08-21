package com.dart.service;

import com.dart.agent.IntentKeys;
import com.dart.agent.curator.CuratedReport;
import com.dart.agent.curator.CuratorAgent;
import com.dart.agent.curator.CuratorTools;
import com.dart.agent.scoring.ScoredResource;
import com.dart.agent.search.SearchAgent;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dart.entity.KnowledgeItem;
import com.dart.entity.Message;
import com.dart.entity.SearchTrace;
import com.dart.exception.ErrorCode;
import com.dart.mapper.KnowledgeItemMapper;
import com.dart.mapper.MessageMapper;
import com.dart.mapper.SearchTraceMapper;
import com.dart.util.RedisKeyConstants;
import com.dart.util.SseEvents;
import com.dart.util.SseUtils;
import com.dart.util.VectorUtils;
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
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
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

    /** 低质兜底报告（抓取失败、摘要级内容）的头部标注块 */
    private static final String DEGRADED_NOTICE =
            "> 说明：部分资源抓取失败，以下内容基于搜索结果摘要整理，内容深度有限，建议点击来源链接查看原文。";

    private final ChatModel chatModel;
    private final SearchAgent searchAgent;
    private final CuratorAgent curatorAgent;
    private final RagService ragService;
    private final EmbeddingService embeddingService;
    private final MessageMapper messageMapper;
    private final StringRedisTemplate redisTemplate;
    private final KnowledgeItemMapper knowledgeItemMapper;
    private final SearchTraceMapper searchTraceMapper;
    private final RelationExtractionService relationExtractionService;
    private final KnowledgeService knowledgeService;
    private final ConversationSummaryService conversationSummaryService;

    /** 未识别意图的默认沉淀标签（与 how_to 标签一致） */
    private static final String DEFAULT_SEDIMENT_TAG = "操作指南";

    /**
     * 查询意图 → 沉淀条目标签（受控集合：每条目恰好 1 个类别标签）。
     */
    private static final Map<String, String> INTENT_TAG = Map.of(
            IntentKeys.QUICK_FACT, "速查",
            IntentKeys.HOW_TO, DEFAULT_SEDIMENT_TAG,
            IntentKeys.DEEP_DIVE, "深入原理",
            IntentKeys.COMPARISON, "对比分析",
            IntentKeys.TROUBLESHOOTING, "排错诊断");

    /** 变体词主题校验阈值：与主词 embedding 余弦低于此值视为跑题，搜索前丢弃 */
    @Value("${agent.search.variant-similarity-threshold:0.5}")
    private double variantSimilarityThreshold = 0.5;

    @Value("${agent.cache.ttl-hours:24}")
    private long cacheTtlHours;

    @Value("${agent.sediment.enabled:true}")
    private boolean sedimentEnabled;

    @Value("${agent.sediment.max-items-per-user:50}")
    private int sedimentMaxItemsPerUser;

    public ChatService(ChatModel chatModel, SearchAgent searchAgent, CuratorAgent curatorAgent,
                       RagService ragService, EmbeddingService embeddingService,
                       MessageMapper messageMapper, StringRedisTemplate redisTemplate,
                       KnowledgeItemMapper knowledgeItemMapper, SearchTraceMapper searchTraceMapper,
                       RelationExtractionService relationExtractionService,
                       KnowledgeService knowledgeService,
                       ConversationSummaryService conversationSummaryService) {
        this.chatModel = chatModel;
        this.searchAgent = searchAgent;
        this.curatorAgent = curatorAgent;
        this.ragService = ragService;
        this.embeddingService = embeddingService;
        this.messageMapper = messageMapper;
        this.redisTemplate = redisTemplate;
        this.knowledgeItemMapper = knowledgeItemMapper;
        this.searchTraceMapper = searchTraceMapper;
        this.relationExtractionService = relationExtractionService;
        this.knowledgeService = knowledgeService;
        this.conversationSummaryService = conversationSummaryService;
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

    public record QueryAnalysis(boolean isTech) {}

    /**
     * 执行完整的搜索-整理流程（供 SSE 端点调用）。
     */
    @Transactional
    public SearchResult executeSearchAndCurate(String query, Long userId,
                                                Long conversationId, SseEmitter emitter) {
        long startNanos = System.nanoTime();
        // 1. 持久化用户消息
        Message userMsg = new Message();
        userMsg.setConversationId(conversationId);
        userMsg.setRole("user");
        userMsg.setContent(query);
        userMsg.setCreatedAt(LocalDateTime.now());
        messageMapper.insert(userMsg);

        // 1.5 查询缓存检查（原始查询规范化 key；命中直接返回，跳过全流程，不记链路）
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
            recordTrace(userId, conversationId, userMsg.getId(), query, pre.intent(),
                    false, null, null, List.of(), "error", "non_tech_query", startNanos);
            return errorResult;
        }

        // 优化后的查询用于 RAG/搜索/整理；用户消息持久化仍存原文
        String effectiveQuery = pre.optimized();

        // 2.5 续答路由：LLM 判定为续答意图（指代上文/要求基于上文解释）→ 基于会话记忆回答，不搜索
        if (pre.followUp()) {
            Optional<String> answer = generateMemoryAnswer(conversationId, userId, query);
            if (answer.isPresent()) {
                SseUtils.sendStatus(emitter, "curating", "正在基于会话记忆回答...");
                SseUtils.sendEvent(emitter, SseEvents.CONTENT, answer.get());
                SearchResult result = SearchResult.followUpAnswer(answer.get());
                persistAssistantMessage(conversationId, result);
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
        SseUtils.sendStatus(emitter, "rag_check", "正在检索知识库...");
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
            String enQuery = buildEnglishQuery(effectiveQuery);
            if (enQuery == null) {
                enQuery = (pre.optimizedEn() != null && !pre.optimizedEn().isBlank())
                        ? pre.optimizedEn() : null;
            }
            if (enQuery != null && !enQuery.isBlank()) {
                searchQueries.add(enQuery);
            }
            // 变体词先经 embedding 主题校验，跑题词（如 LLM 联想跑偏）在搜索前丢弃
            searchQueries.addAll(filterVariants(effectiveQuery, pre.variants()));
            Future<List<ScoredResource>> searchFuture = parallelExecutor.submit(() ->
                    searchAgent.execute(searchQueries, targetResultsFor(pre.intent()), emitter));

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
                SseUtils.sendEvent(emitter, SseEvents.CONTENT, result.ragAnswer().answer());
                persistAssistantMessage(conversationId, result);
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
                SseUtils.sendError(emitter, ErrorCode.AGENT_EXECUTION_ERROR.getCode(),
                        "搜索服务暂时不可用，请稍后重试");
                SearchResult errorResult = SearchResult.searchResult(
                        errorReport("搜索服务暂时不可用，请稍后重试"), List.of());
                persistAssistantMessage(conversationId, errorResult);
                recordTrace(userId, conversationId, userMsg.getId(), effectiveQuery, pre.intent(),
                        false, null, effectiveQuery, List.of(), "error", "search_exception", startNanos);
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
                            "# " + effectiveQuery
                                    + "\n\n未找到高质量资源。建议补充具体技术名词（如 Kubernetes、Docker、"
                                    + "Spring Cloud、微服务框架）后重试。",
                            List.of(), Instant.now()),
                    List.of());
            persistAssistantMessage(conversationId, emptyResult);
            recordTrace(userId, conversationId, userMsg.getId(), effectiveQuery, pre.intent(),
                    false, null, effectiveQuery, List.of(), "error", "search_empty", startNanos);
            return emptyResult;
        }

        // 5. Curator Agent（意图透传为报告风格要求；会话摘要注入延续前文叙述）
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
            report = degraded
                    ? curatorAgent.curate(effectiveQuery, searchResults, emitter,
                            pre.intent(), sessionSummary, true)
                    : curatorAgent.curate(effectiveQuery, searchResults, emitter,
                            pre.intent(), sessionSummary);
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
        String fallbackReason = resolveFallbackReason(report.markdown());
        if (ragDegraded) {
            fallbackReason = "rag_insufficient" + (fallbackReason == null ? "" : "," + fallbackReason);
        }
        if (degraded) {
            fallbackReason = "low_quality_fallback" + (ragDegraded ? ",rag_insufficient" : "");
        }
        recordTrace(userId, conversationId, userMsg.getId(), effectiveQuery, pre.intent(),
                false, null, effectiveQuery, searchResults, "search",
                fallbackReason, startNanos);
        Message assistantMessage = persistAssistantMessage(conversationId, result);
        if (assistantMessage != null) {
            final Message msg = assistantMessage;
            final CuratedReport finalReport = report;
            final Long finalUserId = userId;
            final boolean finalDegraded = degraded;
            Runnable followUpAndCache = () -> generateFollowUpAndUpdate(
                    msg, finalReport, query, emitter, finalUserId, pre.intent(), finalDegraded);
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        followUpAndCache.run();
                    }
                });
            } else {
                followUpAndCache.run();
            }
        }
        return result;
    }

    /**
     * 生成追问 → SSE 追加 → 更新消息；随后（无论追问成败）写入缓存并沉淀知识库。
     * 整体失败仅告警，不影响报告主流程。degraded（低质兜底）时跳过追问/缓存/沉淀：
     * 低质内容上生成追问=双重低质；低质报告污染知识库与 24h 缓存。
     */
    private void generateFollowUpAndUpdate(Message assistantMessage, CuratedReport report,
                                           String query, SseEmitter emitter, Long userId,
                                           String intent, boolean degraded) {
        String markdown = report.markdown();
        try {
            if (!degraded) {
                String followUp = generateFollowUp(query, markdown,
                        assistantMessage.getConversationId(), userId);
                if (followUp != null && !followUp.isBlank()) {
                    SseUtils.sendStatus(emitter, "curating", "正在生成追问建议...");
                    SseUtils.sendEvent(emitter, SseEvents.CONTENT,
                            "\n\n---\n\n## 进一步探索\n\n" + followUp);
                    CuratedReport withFollowUp = appendFollowUp(report, followUp);
                    assistantMessage.setContent(withFollowUp.markdown());
                    messageMapper.updateById(assistantMessage);
                    markdown = withFollowUp.markdown();
                }
            }
        } catch (Exception e) {
            log.warn("追问生成失败", e);
        } finally {
            if (!degraded) {
                writeCache(query, markdown, report.sources());
                sedimentReport(userId, report.title(), query, markdown, report.sources(), intent);
            }
            // 摘要更新必须传原始报告（不含追问段），追问段会污染摘要
            if (assistantMessage.getConversationId() != null) {
                conversationSummaryService.updateSummary(userId,
                        assistantMessage.getConversationId(), query, report.markdown());
            }
        }
    }

    /**
     * 报告自动沉淀（预灌 RAG）：无占位节且未达数量上限时，将报告写入用户知识库，
     * 后续相似查询直接 RAG 命中。Embedding 分块向量化较慢（约 10-30s），异步执行不阻塞主流程。
     * 沉淀条目标签取意图对应的受控类别标签（每条目恰好 1 个，防止标签爆炸）。
     */
    private void sedimentReport(Long userId, String title, String query, String markdown,
                                List<String> sources, String intent) {
        if (!sedimentEnabled || markdown == null || markdown.isBlank()) {
            return;
        }
        if (markdown.contains(CuratorTools.SECTION_FAILED_MARKER)) {
            log.debug("报告含占位节，跳过沉淀: query={}", query);
            return;
        }
        try {
            Long count = knowledgeItemMapper.selectCount(
                    new LambdaQueryWrapper<KnowledgeItem>().eq(KnowledgeItem::getUserId, userId));
            if (sedimentMaxItemsPerUser > 0 && count != null && count >= sedimentMaxItemsPerUser) {
                log.debug("知识库已达上限 {} 条，跳过沉淀: userId={}", sedimentMaxItemsPerUser, userId);
                return;
            }
            String sourceUrl = pickMainSource(sources);
            ScoredResource scores = computeSedimentScores(sourceUrl, markdown);
            String tag = INTENT_TAG.getOrDefault(intent, DEFAULT_SEDIMENT_TAG);
            String finalTitle = title == null || title.isBlank() ? query : title;
            Thread.startVirtualThread(() -> {
                try {
                    // 相似条目合并：近似重复（关键词全等/向量 ≥0.85）更新旧条目而非新增，防知识库膨胀
                    Optional<KnowledgeItem> similar = knowledgeService.findSimilarItem(
                            userId, finalTitle, markdown);
                    if (similar.isPresent()) {
                        Long itemId = similar.get().getId();
                        knowledgeService.mergeItem(userId, itemId, finalTitle, markdown);
                        embeddingService.rebuildChunksForItem(itemId, markdown);
                        relationExtractionService.extractAndPersist(userId, itemId, markdown);
                        try {
                            knowledgeService.linkRelatedItems(userId, itemId);
                        } catch (Exception e) {
                            log.warn("合并后跨条目关联失败，跳过: itemId={}", itemId, e);
                        }
                        log.info("相似条目已合并更新: userId={}, itemId={}, query={}", userId, itemId, query);
                        return;
                    }
                    List<Long> itemIds =
                            embeddingService.embedAndPersist(userId, finalTitle, markdown, sourceUrl, scores, tag);
                    // 沉淀成功 → 异步抽取知识关系（单次 LLM 调用，失败仅告警）
                    if (itemIds != null && !itemIds.isEmpty()) {
                        Long itemId = itemIds.getFirst();
                        relationExtractionService.extractAndPersist(userId, itemId, markdown);
                        // 与现有条目建立跨条目关联（标题子串/向量相似度双通道）
                        try {
                            knowledgeService.linkRelatedItems(userId, itemId);
                        } catch (Exception e) {
                            log.warn("跨条目关联失败，跳过: itemId={}", itemId, e);
                        }
                    }
                    log.info("报告已沉淀到知识库: userId={}, query={}", userId, query);
                } catch (Exception e) {
                    log.warn("报告沉淀到知识库失败: query={}", query, e);
                }
            });
        } catch (Exception e) {
            log.warn("报告沉淀前置检查失败: query={}", query, e);
        }
    }

    /**
     * 主要来源选取：报告多来源聚合，取权威度最高者（非盲目取第一个）。
     */
    private static String pickMainSource(List<String> sources) {
        if (sources == null || sources.isEmpty()) {
            return "";
        }
        com.dart.agent.scoring.AuthorityScorer authority = new com.dart.agent.scoring.AuthorityScorer();
        return sources.stream()
                .max(java.util.Comparator.comparingInt(authority::score))
                .orElse(sources.getFirst());
    }

    /**
     * 沉淀评分（替代硬编码 4,4,4,4）：T=4（实时搜索）、A=域名权威、D=报告深度、M=4（量身生成）。
     */
    private static ScoredResource computeSedimentScores(String sourceUrl, String markdown) {
        int scoreT = 4;
        int scoreA = sourceUrl.isBlank() ? 1 : new com.dart.agent.scoring.AuthorityScorer().score(sourceUrl);
        int scoreD;
        int len = markdown == null ? 0 : markdown.length();
        if (len >= 8000) {
            scoreD = 5;
        } else if (len >= 5000) {
            scoreD = 4;
        } else if (len >= 3000) {
            scoreD = 3;
        } else {
            scoreD = 2;
        }
        int scoreM = 4;
        double weighted = 0.15 * scoreT + 0.30 * scoreA + 0.20 * scoreD + 0.35 * scoreM;
        return new ScoredResource(sourceUrl, "", markdown, scoreT, scoreA, scoreD, scoreM,
                Math.round(weighted * 100.0) / 100.0);
    }

    /**
     * 从报告 markdown 判定降级原因（复用沉淀条件的占位文本模式，零侵入）。
     */
    private static String resolveFallbackReason(String markdown) {
        if (markdown == null) {
            return null;
        }
        if (markdown.contains(CuratorTools.SECTION_FAILED_MARKER)) {
            return "section_failed";
        }
        if (markdown.contains("AI 报告生成暂时不可用")) {
            return "report_fallback";
        }
        return null;
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
            searchTraceMapper.insert(trace);
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

    // ─── 续答回答（会话记忆路径）────────────────────────────────

    @Value("${agent.memory.recent-reports:2}")
    private int memoryRecentReports;

    @Value("${agent.memory.report-chars-per-round:3000}")
    private int memoryReportCharsPerRound;

    /**
     * 基于会话记忆回答模糊输入：会话摘要 + 最近 N 轮 assistant 报告 → LLM 单次调用。
     * 失败返回 Optional.empty()，调用方回退正常搜索流程（fail-open）。
     */
    private Optional<String> generateMemoryAnswer(Long conversationId, Long userId, String query) {
        try {
            String summary = conversationSummaryService.getSummary(userId, conversationId);
            List<Message> recent = messageMapper.selectList(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Message>()
                            .eq(Message::getConversationId, conversationId)
                            .eq(Message::getRole, "assistant")
                            .orderByDesc(Message::getCreatedAt)
                            .last("LIMIT " + memoryRecentReports));
            StringBuilder reports = new StringBuilder();
            for (Message m : recent) {
                String content = m.getContent();
                if (content != null && content.length() > memoryReportCharsPerRound) {
                    content = content.substring(0, memoryReportCharsPerRound);
                }
                reports.append("<report>\n").append(content).append("\n</report>\n\n");
            }
            String sanitized = query.replace("<", "&lt;").replace(">", "&gt;");
            String prompt = """
                    你是会话记忆问答助手。用户在当前会话语境中提出了模糊/指代性问题，
                    请结合历史报告回答，不要假设存在未提供的信息。

                    历史会话摘要：<session_memory>
                    %s
                    </session_memory>

                    最近报告：
                    %s
                    用户问题：<user_query>%s</user_query>

                    要求：
                    - 直接回答问题；历史报告不足以回答时，如实说明缺少哪部分信息
                    - 引用必须来自历史报告，不得虚构来源
                    - 简洁、准确的中文回答
                    """.formatted(
                    summary == null || summary.isBlank() ? "（无）" : summary,
                    reports.isEmpty() ? "（无历史报告）" : reports,
                    sanitized);
            String response = chatModel.call(new Prompt(new UserMessage(prompt)))
                    .getResults().getFirst().getOutput().getText().trim();
            return response.isBlank() ? Optional.empty() : Optional.of(response);
        } catch (Exception e) {
            log.warn("续答回答生成失败: query={}", query, e);
            return Optional.empty();
        }
    }

    // ─── 追问生成 ────────────────────────────────────────────

    /**
     * 基于知识图谱报告内容，由 LLM 生成 2-3 条自然语言追问建议。
     * 失败时返回 null，不影响主流程。
     */
    private String generateFollowUp(String query, String reportMarkdown,
                                    Long conversationId, Long userId) {
        try {
            String truncated = reportMarkdown.length() > FOLLOW_UP_MAX_REPORT_CHARS
                    ? reportMarkdown.substring(0, FOLLOW_UP_MAX_REPORT_CHARS)
                    : reportMarkdown;
            String sanitized = query.replace("<", "&lt;").replace(">", "&gt;");
            String summary = null;
            try {
                summary = conversationSummaryService.getSummary(userId, conversationId);
            } catch (Exception e) {
                log.warn("追问会话摘要读取失败，跳过注入: conversationId={}", conversationId, e);
            }
            String memorySection = (summary == null || summary.isBlank()) ? ""
                    : "历史会话摘要（可引用前几轮已讨论内容，追问应延续而非重复）：\n<session_memory>\n"
                            + summary + "\n</session_memory>\n\n";
            String prompt = """
                    %s以下是一份知识学习报告（含 Mermaid 知识图谱和结构化章节），根据报告已覆盖的内容，提出2-3个读者可能感兴趣的追问方向。
                    追问应与知识图谱的分支主题相关但不重复已讲内容，以自然对话语气、中文写出。

                    用户查询：<user_query>%s</user_query>
                    报告摘要：
                    %s

                    严格按以下格式输出（每条追问以"- "开头，不要编号，不要加粗，不要空行）：
                    - 追问问题1
                    - 追问问题2
                    - 追问问题3
                    """.formatted(memorySection, sanitized, truncated);
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
            } else if (result.isFollowUpHit()) {
                assistantMsg.setContent(result.followUpAnswer());
                assistantMsg.setMetadata("{\"type\":\"followup\"}");
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

    /**
     * 变体词主题校验：与主词 embedding 余弦相似度低于阈值视为跑题，搜索前丢弃。
     * fail-open：embedding 失败/返回 null 时保留全部变体词（校验是增强，失败不砍搜索）。
     */
    private List<String> filterVariants(String mainQuery, List<String> variants) {
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

    public record QueryPreProcess(boolean isTech, String optimized, List<String> variants,
                                  String intent, boolean followUp, String optimizedEn) {}

    /**
     * 合并领域判断、意图分类与提示词优化为一次 LLM 调用（JSON 输出 {isTech, optimized, variants, intent, isFollowUp}）。
     * 失败/解析失败降级：isTech=true（放行）、optimized=原查询、variants=空列表、intent=how_to、followUp=false。
     * 用户输入用 XML 标签包裹防注入。
     */
    public QueryPreProcess preProcessQuery(String query) {
        return preProcessQuery(query, "");
    }

    /**
     * 合并领域判断、意图分类与提示词优化为一次 LLM 调用（JSON 输出 {isTech, optimized, variants, intent, isFollowUp}）。
     * 注入会话摘要后 LLM 可解析指代（"接着讲讲它"→具体 query）并判定续答意图。
     * 失败/解析失败降级：isTech=true（放行）、optimized=原查询、variants=空列表、intent=how_to、followUp=false。
     */
    public QueryPreProcess preProcessQuery(String query, String summary) {
        try {
            String sanitized = query.replace("<", "&lt;").replace(">", "&gt;");
            String sanitizedSummary = (summary == null ? "" : summary).replace("<", "&lt;").replace(">", "&gt;");
            String prompt = buildPreProcessPrompt(sanitized, sanitizedSummary);
            String response = chatModel.call(new Prompt(new UserMessage(prompt)))
                    .getResults().getFirst().getOutput().getText().trim();
            boolean isTech = parseIsTech(response);
            boolean followUp = isTech && parseIsFollowUp(response);
            String optimized = parseOptimized(response);
            List<String> variants = parseVariants(response);
            // 非技术查询意图强制归一化为 non_tech（以 isTech 判断为准）
            String intent = isTech ? parseIntent(response) : "non_tech";
            // 服务端锚点兜底：LLM 未遵守锚点指令时（抽象词/歧义词无具体技术名），自动补充主流锚点
            String finalOptimized = (optimized == null || optimized.isBlank()) ? query : optimized;
            String optimizedEn = parseOptimizedEn(response);
            if (isTech && !followUp) {
                finalOptimized = applyTechAnchors(finalOptimized);
                // optimizedEn 违规含中文（中英混合实测仍被 Bing 污染）→ 丢弃，走词表降级
                if (optimizedEn != null && HAN_PATTERN.matcher(optimizedEn).find()) {
                    log.warn("optimizedEn 含中文字符（违规中英混合），丢弃改用词表降级: {}", optimizedEn);
                    optimizedEn = null;
                }
            }
            return new QueryPreProcess(isTech, finalOptimized, variants, intent, followUp, optimizedEn);
        } catch (Exception e) {
            log.warn("LLM 前置判断与提示词优化失败，降级为技术查询+原文", e);
            return new QueryPreProcess(true, query, List.of(), "how_to", false, null);
        }
    }

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
            Map.entry("自动配置", List.of("Spring Boot"))
    );

    /**
     * 歧义中文技术词 → 英文检索词（LLM optimizedEn 缺失时的词表降级）。
     */
    private static final Map<String, String> EN_ANCHOR_MAP = Map.ofEntries(
            Map.entry("微服务", "microservices"), Map.entry("云原生", "cloud native"),
            Map.entry("容器", "container"), Map.entry("网关", "api gateway"),
            Map.entry("消息队列", "message queue"), Map.entry("缓存", "cache"),
            Map.entry("数据库", "database"), Map.entry("搜索引擎", "search engine"),
            Map.entry("前端", "frontend"), Map.entry("大模型", "large language model"),
            Map.entry("三级缓存", "spring three-level cache"), Map.entry("AOP", "spring aop"),
            Map.entry("自动配置", "spring boot autoconfiguration"),
            Map.entry("分布式", "distributed system"), Map.entry("高并发", "high concurrency"),
            Map.entry("并发", "concurrency"),
            Map.entry("部署", "deployment"), Map.entry("集群", "cluster"));

    private static final java.util.regex.Pattern HAN_PATTERN = java.util.regex.Pattern.compile("\\p{IsHan}");

    /**
     * 词表降级英文检索词生成：仅输出歧义中文技术词的英文映射短词（长键优先、子串跳过）。
     * 刻意不做意图短尾/原英文 token 组合——Bing 对长句查询（无论中英、含锚点词组合）实测
     * 易返回随机无关结果（漂移污染），短英文技术词最稳定；上下文由主词/变体词承担。
     * 无歧义词命中返回 null（由 LLM optimizedEn 翻译兜底）。
     */
    static String buildEnglishQuery(String query) {
        if (query == null || query.isBlank()) {
            return null;
        }
        List<String> parts = new ArrayList<>();
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

    /**
     * 解析 isFollowUp 字段；字段缺失或畸形一律降级 false（与 parseIntent 兼容旧格式 JSON 风格一致）。
     */
    private static boolean parseIsFollowUp(String json) {
        if (json == null || json.isBlank()) {
            return false;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"isFollowUp\"\\s*:\\s*(true|false)").matcher(json);
        return m.find() && Boolean.parseBoolean(m.group(1));
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

    private static String parseOptimizedEn(String json) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"optimizedEn\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
        return m.find() ? m.group(1) : null;
    }

    /**
     * 解析 intent 字段；字段缺失或畸形一律降级为 how_to（兼容旧格式 JSON），不抛异常。
     */
    private static String parseIntent(String json) {
        if (json == null || json.isBlank()) {
            return "how_to";
        }
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"intent\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
        return m.find() ? m.group(1) : "how_to";
    }

    /**
     * 意图 → 搜索结果量：quick_fact 收窄（简洁回答）、deep_dive/comparison 扩大（召回更全）、其余默认。
     */
    private static int targetResultsFor(String intent) {
        return switch (intent == null ? "" : intent) {
            case IntentKeys.QUICK_FACT -> 5;
            case IntentKeys.DEEP_DIVE, IntentKeys.COMPARISON -> 12;
            default -> 8;
        };
    }

    /**
     * 解析 variants 数组；字段缺失或畸形一律降级为空列表（只用主词搜索），不抛异常。
     */
    private static List<String> parseVariants(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"variants\"\\s*:\\s*\\[([^\\]]*)\\]").matcher(json);
        if (!m.find()) {
            return List.of();
        }
        List<String> variants = new ArrayList<>();
        java.util.regex.Matcher item = java.util.regex.Pattern
                .compile("\"([^\"]*)\"").matcher(m.group(1));
        while (item.find()) {
            String v = item.group(1).trim();
            if (!v.isBlank()) {
                variants.add(v);
            }
        }
        return variants;
    }

    // ─── 工具方法 ────────────────────────────────────────────

    private CuratedReport errorReport(String message) {
        return new CuratedReport("搜索错误", "# 搜索错误\n\n" + message, List.of(), Instant.now());
    }
}
