package com.dart.agent.curator;

import com.dart.broadcast.SseSink;
import com.dart.agent.scoring.ScoredResource;
import com.dart.exception.ErrorCode;
import com.dart.util.SseEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    /**
     * @param draftConsumer 报告生成过程中的草稿快照回调（透传给 CuratorTools，
     *                      5s 节流）；null 表示不回调
     */
    public CuratedReport curate(String query, List<ScoredResource> searchResults,
                                SseSink sink, String intent, String sessionSummary,
                                boolean degraded, Consumer<String> draftConsumer) {
        try {
            sink.sendStatus("curating", "正在整理归纳...");

            List<ScoredResource> deduped = curatorTools.deduplicate(searchResults);
            if (deduped.isEmpty()) {
                return curatorTools.fallbackReport(query, searchResults);
            }

            CuratedReport report = degraded
                    ? curatorTools.generateReport(query, deduped, sink, intent, sessionSummary, true, draftConsumer)
                    : curatorTools.generateReport(query, deduped, sink, intent, sessionSummary, false, draftConsumer);

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

            sink.sendEvent(SseEvents.CURATED_REPORT, report);
            return report;

        } catch (Exception e) {
            log.error("Curator Agent 执行异常: {}", query, e);
            sink.sendError(ErrorCode.AGENT_EXECUTION_ERROR.getCode(),
                    ErrorCode.AGENT_EXECUTION_ERROR.getMessage());
            return curatorTools.fallbackReport(query, searchResults != null ? searchResults : List.of());
        }
    }
}
