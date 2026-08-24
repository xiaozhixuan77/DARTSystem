package com.dart.agent.curator;

import com.dart.agent.scoring.ScoredResource;
import com.dart.exception.ErrorCode;
import com.dart.util.SseEvents;
import com.dart.util.SseUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.function.Consumer;

/**
 * Curator Agent — 整理编排：去重 → LLM 生成报告 → 输出校验 → 降级兜底。
 */
public class CuratorAgent {

    private static final Logger log = LoggerFactory.getLogger(CuratorAgent.class);

    private final CuratorTools curatorTools;

    public CuratorAgent(CuratorTools curatorTools) {
        this.curatorTools = curatorTools;
    }

    public CuratedReport curate(String query, List<ScoredResource> searchResults, SseEmitter emitter) {
        return curate(query, searchResults, emitter, null);
    }

    public CuratedReport curate(String query, List<ScoredResource> searchResults,
                                SseEmitter emitter, String intent) {
        return curate(query, searchResults, emitter, intent, null);
    }

    /**
     * @param intent 查询意图（quick_fact/how_to/deep_dive/comparison/troubleshooting/non_tech），
     *               透传给报告生成作为风格要求；null 表示无风格要求
     * @param sessionSummary 会话滚动摘要（长记忆），供报告延续前文叙述；null 表示无历史
     */
    public CuratedReport curate(String query, List<ScoredResource> searchResults,
                                SseEmitter emitter, String intent, String sessionSummary) {
        return curate(query, searchResults, emitter, intent, sessionSummary, false);
    }

    /**
     * @param degraded true = 素材为搜索摘要级（抓取失败兜底），报告精简约束注入
     */
    public CuratedReport curate(String query, List<ScoredResource> searchResults,
                                SseEmitter emitter, String intent, String sessionSummary,
                                boolean degraded) {
        return curate(query, searchResults, emitter, intent, sessionSummary, degraded, null);
    }

    /**
     * @param draftConsumer 报告生成过程中的草稿快照回调（透传给 CuratorTools，
     *                      5s 节流）；null 表示不回调
     */
    public CuratedReport curate(String query, List<ScoredResource> searchResults,
                                SseEmitter emitter, String intent, String sessionSummary,
                                boolean degraded, Consumer<String> draftConsumer) {
        try {
            SseUtils.sendStatus(emitter, "curating", "正在整理归纳...");

            List<ScoredResource> deduped = curatorTools.deduplicate(searchResults);
            if (deduped.isEmpty()) {
                return curatorTools.fallbackReport(query, searchResults);
            }

            CuratedReport report = degraded
                    ? curatorTools.generateReport(query, deduped, emitter, intent, sessionSummary, true, draftConsumer)
                    : curatorTools.generateReport(query, deduped, emitter, intent, sessionSummary, false, draftConsumer);

            if (!curatorTools.validateReport(report)) {
                // 单次 LLM 精简重写抢救；重写成功且通过校验则替换，否则降级 fallback（无循环）
                String rewritten = curatorTools.regenerateReport(query, deduped);
                CuratedReport rewrittenReport = rewritten == null ? null
                        : new CuratedReport(query, rewritten,
                                deduped.stream().map(ScoredResource::getUrl).toList(),
                                report.generatedAt());
                if (rewrittenReport != null && curatorTools.validateReport(rewrittenReport)) {
                    log.info("报告校验失败后单次重写成功");
                    report = rewrittenReport;
                } else {
                    log.warn("报告校验失败且重写不可用，降级使用 fallbackReport");
                    report = curatorTools.fallbackReport(query, deduped);
                }
            }

            SseUtils.sendEvent(emitter, SseEvents.CURATED_REPORT, report);
            return report;

        } catch (Exception e) {
            log.error("Curator Agent 执行异常: {}", query, e);
            SseUtils.sendError(emitter, ErrorCode.AGENT_EXECUTION_ERROR.getCode(),
                    ErrorCode.AGENT_EXECUTION_ERROR.getMessage());
            return curatorTools.fallbackReport(query, searchResults != null ? searchResults : List.of());
        }
    }
}
