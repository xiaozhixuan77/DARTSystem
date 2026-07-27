package com.dart.agent.search;

import com.dart.agent.scoring.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 搜索工具集 — 组合 SearXNG 搜索、Jsoup 并行抓取、四维评分。
 */
public class SearchTools {

    private static final Logger log = LoggerFactory.getLogger(SearchTools.class);

    private final BingSearchClient bingSearchClient;
    private final PageFetcher pageFetcher;
    private final QualityScorer qualityScorer;
    private final TimelinessScorer timelinessScorer;
    private final AuthorityScorer authorityScorer;
    private final DepthScorer depthScorer;
    private final MatchScorer matchScorer;

    public SearchTools(BingSearchClient bingSearchClient, PageFetcher pageFetcher,
                       QualityScorer qualityScorer, MatchScorer matchScorer) {
        this.bingSearchClient = bingSearchClient;
        this.pageFetcher = pageFetcher;
        this.qualityScorer = qualityScorer;
        this.timelinessScorer = new TimelinessScorer();
        this.authorityScorer = new AuthorityScorer();
        this.depthScorer = new DepthScorer();
        this.matchScorer = matchScorer;
    }

    public List<SearchResult> searchWeb(String query, int maxResults) {
        try {
            return bingSearchClient.search(query, maxResults);
        } catch (Exception e) {
            log.error("搜索异常: query={}", query, e);
            return List.of();
        }
    }

    public List<PageContent> fetchPages(List<String> urls) {
        if (urls == null || urls.isEmpty()) {
            return List.of();
        }

        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            List<CompletableFuture<PageContent>> futures = urls.stream()
                    .map(url -> CompletableFuture.supplyAsync(() -> {
                        try {
                            return pageFetcher.fetch(url);
                        } catch (Exception e) {
                            log.warn("页面抓取失败（非阻断）: {}", url, e);
                            return null;
                        }
                    }, executor))
                    .toList();

            List<PageContent> results = new ArrayList<>();
            for (CompletableFuture<PageContent> future : futures) {
                try {
                    PageContent page = future.get(15, TimeUnit.SECONDS);
                    if (page != null) {
                        results.add(page);
                    }
                } catch (Exception e) {
                    log.debug("抓取任务超时或异常", e);
                }
            }
            return results;
        } finally {
            executor.shutdownNow();
        }
    }

    public List<ScoredResource> scoreResources(String query, List<PageContent> pages) {
        if (pages == null || pages.isEmpty()) {
            return List.of();
        }

        return pages.stream()
                .map(p -> {
                    int t = timelinessScorer.score(p.getYear() != null ? p.getYear() : 0);
                    int a = authorityScorer.score(p.getUrl());
                    int d = depthScorer.score(p.getWordCount(), p.getCodeBlockCount(),
                            p.hasDiagram(), p.hasExercise());
                    int m = scoreMatch(query, p);
                    return qualityScorer.evaluate(p.getUrl(), p.getTitle(), p.getContent(), t, a, d, m);
                })
                .filter(r -> qualityScorer.isAboveThreshold(r.getWeightedScore()))
                .sorted(Comparator.comparingDouble(ScoredResource::getWeightedScore).reversed())
                .toList();
    }

    private int scoreMatch(String query, PageContent page) {
        if (matchScorer != null) {
            try {
                return matchScorer.evaluate(query, page.getContent());
            } catch (Exception e) {
                log.warn("Match 评分失败，使用默认值 3: {}", page.getUrl(), e);
                return 3;
            }
        }
        return 3;
    }
}
