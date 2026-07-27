package com.dart.agent.search;

import com.dart.agent.scoring.ScoredResource;
import com.dart.exception.ErrorCode;
import com.dart.util.SseEvents;
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
            List<SearchResult> rawResults = searchTools.searchWeb(query, maxResults);
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

            // Step 3: 并行抓取（失败时用搜索摘要兜底）
            SseUtils.sendStatus(emitter, "fetching", "正在抓取页面内容...");
            List<com.dart.agent.scoring.PageContent> pages = searchTools.fetchPages(urls);
            // 抓取失败的 URL 用搜索摘要作为内容
            java.util.Set<String> fetchedUrls = pages.stream()
                    .map(com.dart.agent.scoring.PageContent::getUrl)
                    .collect(java.util.stream.Collectors.toSet());
            for (SearchResult sr : rawResults) {
                if (!fetchedUrls.contains(sr.url()) && sr.snippet() != null && !sr.snippet().isBlank()) {
                    pages.add(new com.dart.agent.scoring.PageContent(
                            sr.url(), sr.title(), sr.snippet(),
                            sr.snippet().length(), 0, false, false,
                            java.time.Year.now().getValue()));
                }
            }
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
            SseUtils.sendEvent(emitter, SseEvents.SEARCH_RESULT, top);
            return top;

        } catch (Exception e) {
            log.error("Search Agent 执行异常: {}", query, e);
            SseUtils.sendError(emitter, ErrorCode.AGENT_EXECUTION_ERROR.getCode(),
                    ErrorCode.AGENT_EXECUTION_ERROR.getMessage());
            return List.of();
        }
    }
}
