package com.dart.agent.search;

import com.dart.agent.scoring.ScoredResource;
import com.dart.exception.ErrorCode;
import com.dart.util.SseEvents;
import com.dart.util.SseUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Search Agent — 搜索编排：Bing 搜索 → 并行抓取 → 四维评分 → 筛选 Top-N。
 */
public class SearchAgent {

    private static final Logger log = LoggerFactory.getLogger(SearchAgent.class);

    // 合并后抓取上限：多组检索词各返回 maxResults 条，防止 URL 数随组数线性膨胀
    private static final int MAX_FETCH_URLS = 12;

    private final SearchTools searchTools;

    public SearchAgent(SearchTools searchTools) {
        this.searchTools = searchTools;
    }

    public List<ScoredResource> execute(List<String> queries, int maxResults, SseEmitter emitter) {
        try {
            // Step 1: 多组检索词虚拟线程并行搜索（Query 拆解产出的主词 + 变体词），合并去重
            SseUtils.sendStatus(emitter, "searching", "正在搜索相关资源...");
            List<SearchResult> rawResults = searchQueriesInParallel(queries, maxResults);
            if (rawResults.isEmpty()) {
                SseUtils.sendError(emitter, ErrorCode.NO_QUALITY_RESULTS.getCode(),
                        ErrorCode.NO_QUALITY_RESULTS.getMessage());
                return List.of();
            }

            // Step 2: URL 去重（保持首次出现顺序）+ 抓取上限截断；
            // 超限 URL 未抓取但仍走摘要兜底参与评分（rawResults 全量）
            List<String> urls = rawResults.stream()
                    .map(SearchResult::url)
                    .filter(u -> u != null && !u.isBlank())
                    .distinct()
                    .limit(MAX_FETCH_URLS)
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

            // Step 4: 四维评分 + 筛选（用主检索词评估匹配度）
            SseUtils.sendStatus(emitter, "scoring", "正在评估资源质量...");
            List<ScoredResource> scored = searchTools.scoreResources(queries.getFirst(), pages);
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
            log.error("Search Agent 执行异常: {}", queries, e);
            SseUtils.sendError(emitter, ErrorCode.AGENT_EXECUTION_ERROR.getCode(),
                    ErrorCode.AGENT_EXECUTION_ERROR.getMessage());
            return List.of();
        }
    }

    /**
     * 并行执行多组检索词的搜索。主词（queries.getFirst()）结果优先：
     * 达标（≥maxResults）时变体词结果不混入——少而精；未达标时变体词按序补足。
     * 变体词结果去重并排除与主词重复的 URL。
     */
    private List<SearchResult> searchQueriesInParallel(List<String> queries, int maxResults) {
        if (queries == null || queries.isEmpty()) {
            return List.of();
        }
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            List<Future<List<SearchResult>>> futures = queries.stream()
                    .map(q -> executor.submit(() -> searchTools.searchWeb(q, maxResults)))
                    .toList();
            List<SearchResult> primary = new ArrayList<>();
            List<SearchResult> variants = new ArrayList<>();
            for (int i = 0; i < futures.size(); i++) {
                List<SearchResult> results;
                try {
                    results = futures.get(i).get(15, TimeUnit.SECONDS);
                } catch (Exception e) {
                    log.warn("单组检索词搜索失败（非阻断）: query={}", queries.get(i), e);
                    continue;
                }
                if (results == null) {
                    continue;
                }
                if (i == 0) {
                    primary.addAll(results);
                } else {
                    variants.addAll(results);
                }
            }
            List<SearchResult> primaryUnique = uniqueByUrl(primary);
            if (primaryUnique.isEmpty()) {
                return List.of();
            }
            if (primaryUnique.size() >= maxResults) {
                return primaryUnique;
            }
            // 主词未达标：变体词按序补足（URL 去重 + 排除与主词重复）
            List<SearchResult> merged = new ArrayList<>(primaryUnique);
            for (SearchResult vr : variants) {
                if (merged.stream().noneMatch(r -> r.url().equals(vr.url()))) {
                    merged.add(vr);
                }
            }
            return merged;
        } finally {
            executor.shutdownNow();
        }
    }

    private static List<SearchResult> uniqueByUrl(List<SearchResult> results) {
        List<SearchResult> unique = new ArrayList<>();
        for (SearchResult r : results) {
            if (r.url() == null || r.url().isBlank()) {
                continue;
            }
            if (unique.stream().noneMatch(u -> u.url().equals(r.url()))) {
                unique.add(r);
            }
        }
        return unique;
    }
}
