package com.dart.agent.curator;

import com.dart.agent.scoring.ScoredResource;
import com.dart.exception.ErrorCode;
import com.dart.util.SseUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

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
        try {
            SseUtils.sendStatus(emitter, "curating", "正在整理归纳...");

            List<ScoredResource> deduped = curatorTools.deduplicate(searchResults);
            if (deduped.isEmpty()) {
                return curatorTools.fallbackReport(query, searchResults);
            }

            CuratedReport report = curatorTools.generateReport(query, deduped);

            if (!curatorTools.validateReport(report)) {
                log.warn("报告校验失败，降级使用 fallbackReport");
                report = curatorTools.fallbackReport(query, deduped);
            }

            SseUtils.sendEvent(emitter, "score", report);
            return report;

        } catch (Exception e) {
            log.error("Curator Agent 执行异常: {}", query, e);
            SseUtils.sendError(emitter, ErrorCode.AGENT_EXECUTION_ERROR.getCode(),
                    ErrorCode.AGENT_EXECUTION_ERROR.getMessage());
            return curatorTools.fallbackReport(query, searchResults != null ? searchResults : List.of());
        }
    }
}
