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
 * 搜索工具集 — 组合 Bing 搜索、Jsoup 并行抓取、四维评分。
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

        // 单页最长等待：12s 超时 × 2 次尝试 + 0.5s 重试间隔 ≈ 24.5s，封顶 25s
        final int fetchGetTimeoutSeconds = 25;
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
                    PageContent page = future.get(fetchGetTimeoutSeconds, TimeUnit.SECONDS);
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
                // 无关页过滤：M=1（关键词零命中）且 D=1（内容极浅）即使靠 T/A 加权过线也过滤；
                // 仅 M=1 不砍——中文长句查询分词缺陷下真实长文页也会 M=1，需 D 兜底
                .filter(r -> (r.getScoreM() > 1 || r.getScoreD() > 1)
                        && qualityScorer.isAboveThreshold(r.getWeightedScore()))
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
