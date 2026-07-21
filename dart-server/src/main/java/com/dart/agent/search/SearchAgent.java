package com.dart.agent.search;

import com.dart.agent.scoring.ScoredResource;
import com.dart.exception.ErrorCode;
import com.dart.util.SseUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

/**
 * Search Agent — 搜索编排：SearXNG 搜索 → 并行抓取 → 四维评分 → 筛选 Top-N。
 */
public class SearchAgent {

    private static final Logger log = LoggerFactory.getLogger(SearchAgent.class);

    private final SearchTools searchTools;

    public SearchAgent(SearchTools searchTools) {
        this.searchTools = searchTools;
    }

    public List<ScoredResource> execute(String query, int maxResults, SseEmitter emitter) {
        try {
            // Step 1: SearXNG 搜索
            SseUtils.sendStatus(emitter, "searching", "正在搜索相关资源...");
            List<SearchResult> rawResults = searchTools.searchWeb(query, 15);
            if (rawResults.isEmpty()) {
                SseUtils.sendError(emitter, ErrorCode.NO_QUALITY_RESULTS.getCode(),
                        ErrorCode.NO_QUALITY_RESULTS.getMessage());
                return List.of();
            }

            // Step 2: URL 去重
            List<String> urls = rawResults.stream()
                    .map(SearchResult::url)
                    .filter(u -> u != null && !u.isBlank())
                    .distinct()
                    .toList();

            // Step 3: 并行抓取
            SseUtils.sendStatus(emitter, "fetching", "正在抓取页面内容...");
            List<com.dart.agent.scoring.PageContent> pages = searchTools.fetchPages(urls);
            if (pages.isEmpty()) {
                SseUtils.sendError(emitter, ErrorCode.FETCH_PAGE_ERROR.getCode(), "所有页面抓取失败");
                return List.of();
            }

            // Step 4: 四维评分 + 筛选
            SseUtils.sendStatus(emitter, "scoring", "正在评估资源质量...");
            List<ScoredResource> scored = searchTools.scoreResources(query, pages);
            if (scored.isEmpty()) {
                SseUtils.sendError(emitter, ErrorCode.NO_QUALITY_RESULTS.getCode(),
                        ErrorCode.NO_QUALITY_RESULTS.getMessage());
                return List.of();
            }

            // Step 5: 取 Top-N
            List<ScoredResource> top = scored.stream()
                    .limit(Math.min(maxResults, scored.size()))
                    .toList();
            SseUtils.sendEvent(emitter, "search_result", top);
            return top;

        } catch (Exception e) {
            log.error("Search Agent 执行异常: {}", query, e);
            SseUtils.sendError(emitter, ErrorCode.AGENT_EXECUTION_ERROR.getCode(),
                    ErrorCode.AGENT_EXECUTION_ERROR.getMessage());
            return List.of();
        }
    }
}
