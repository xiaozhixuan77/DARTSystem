package com.dart.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dart.agent.IntentKeys;
import com.dart.agent.curator.CuratorTools;
import com.dart.agent.scoring.ScoredResource;
import com.dart.entity.KnowledgeItem;
import com.dart.mapper.KnowledgeItemMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 报告自动沉淀（预灌 RAG）：无占位节且未达数量上限时，将报告写入用户知识库，
 * 后续相似查询直接 RAG 命中。Embedding 分块向量化较慢（约 10-30s），异步执行不阻塞主流程。
 */
@Service
public class SedimentService {

    private static final Logger log = LoggerFactory.getLogger(SedimentService.class);

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

    private final EmbeddingService embeddingService;
    private final KnowledgeItemMapper knowledgeItemMapper;
    private final KnowledgeService knowledgeService;
    private final RelationExtractionService relationExtractionService;
    private final EntityExtractionService entityExtractionService;
    private final EntityService entityService;

    @Value("${agent.sediment.enabled:true}")
    private boolean sedimentEnabled;

    @Value("${agent.sediment.max-items-per-user:50}")
    private int sedimentMaxItemsPerUser;

    public SedimentService(EmbeddingService embeddingService,
                           KnowledgeItemMapper knowledgeItemMapper,
                           KnowledgeService knowledgeService,
                           RelationExtractionService relationExtractionService,
                           EntityExtractionService entityExtractionService,
                           EntityService entityService) {
        this.embeddingService = embeddingService;
        this.knowledgeItemMapper = knowledgeItemMapper;
        this.knowledgeService = knowledgeService;
        this.relationExtractionService = relationExtractionService;
        this.entityExtractionService = entityExtractionService;
        this.entityService = entityService;
    }

    public void sedimentReport(Long userId, String title, String query, String markdown,
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
                    // 实体抽取（topic + concept）：LLM 失败返回空，不影响沉淀
                    EntityExtractionService.ExtractedEntities extracted =
                            entityExtractionService.extract(finalTitle, markdown);
                    List<Long> entityIds = entityService.upsertEntities(userId, extracted);
                    // 相似条目合并：近似重复（关键词全等/向量 ≥0.85）更新旧条目而非新增，防知识库膨胀
                    Optional<KnowledgeItem> similar = knowledgeService.findSimilarItem(
                            userId, finalTitle, markdown);
                    if (similar.isPresent()) {
                        Long itemId = similar.get().getId();
                        knowledgeService.mergeItem(userId, itemId, finalTitle, markdown);
                        embeddingService.rebuildChunksForItem(itemId, markdown);
                        relationExtractionService.extractAndPersist(userId, itemId, markdown);
                        entityService.linkItems(userId, itemId, entityIds);
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
                        entityService.linkItems(userId, itemId, entityIds);
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
     * 从报告 markdown 判定降级原因（复用沉淀条件的占位文本模式，零侵入）。
     */
    public static String resolveFallbackReason(String markdown) {
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
}
