package com.dart.agent.search;

import com.dart.broadcast.SseSink;
import com.dart.agent.scoring.ScoredResource;
import com.dart.exception.ErrorCode;
import com.dart.util.SseEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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

    // 歧义中文技术词 → 英文检索词（与 ChatService 的降级映射一致，独立副本避免跨层传参；
    // 值必须实测稳定——microservices 词系在 Bing 双域随机污染，spring cloud 词系稳定）
    private static final Map<String, String> EN_ANCHOR_MAP = Map.ofEntries(
            Map.entry("微服务", "spring cloud"), Map.entry("云原生", "cloud native"),
            Map.entry("容器", "container"), Map.entry("网关", "api gateway"),
            Map.entry("消息队列", "message queue"), Map.entry("缓存", "cache"),
            Map.entry("数据库", "database"), Map.entry("搜索引擎", "search engine"),
            Map.entry("前端", "frontend"), Map.entry("大模型", "large language model"),
            Map.entry("三级缓存", "spring three-level cache"), Map.entry("AOP", "spring aop"),
            Map.entry("自动配置", "spring boot autoconfiguration"),
            Map.entry("分布式", "distributed system"), Map.entry("高并发", "high concurrency"),
            Map.entry("并发", "concurrency"), Map.entry("线程", "multithreading"),
            Map.entry("部署", "deployment"), Map.entry("集群", "cluster"));

    private final SearchTools searchTools;

    public SearchAgent(SearchTools searchTools) {
        this.searchTools = searchTools;
    }

    public List<ScoredResource> execute(List<String> queries, int maxResults, SseSink sink) {
        try {
            // Step 1: 多组检索词虚拟线程并行搜索（Query 拆解产出的主词 + 变体词），合并去重
            sink.sendStatus("searching", "正在搜索相关资源...");
            List<SearchResult> rawResults = searchQueriesInParallel(queries, maxResults);
            if (rawResults.isEmpty()) {
                sink.sendError(ErrorCode.NO_QUALITY_RESULTS.getCode(),
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
            sink.sendStatus("fetching", "正在抓取页面内容...");
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
                sink.sendError(ErrorCode.FETCH_PAGE_ERROR.getCode(), "所有页面抓取失败");
                return List.of();
            }

            // Step 4: 四维评分 + 筛选（用主检索词评估匹配度）
            sink.sendStatus("scoring", "正在评估资源质量...");
            List<ScoredResource> scored = searchTools.scoreResources(queries.getFirst(), pages);
            if (scored.isEmpty()) {
                // 低质兜底：raw 结果相关（逐条命中）但评分过滤光（多为抓取失败）→ 用摘要构造降级项
                List<ScoredResource> degraded = buildDegradedResults(
                        queries.getFirst(), rawResults, maxResults);
                if (!degraded.isEmpty()) {
                    sink.sendStatus("scoring", "抓取受限，基于搜索结果摘要整理...");
                    sink.sendEvent(SseEvents.SEARCH_RESULT, degraded);
                    return degraded;
                }
                sink.sendError(ErrorCode.NO_QUALITY_RESULTS.getCode(),
                        ErrorCode.NO_QUALITY_RESULTS.getMessage());
                return List.of();
            }

            // Step 5: 取 Top-N
            List<ScoredResource> top = scored.stream()
                    .limit(Math.min(maxResults, scored.size()))
                    .toList();
            sink.sendEvent(SseEvents.SEARCH_RESULT, top);
            return top;

        } catch (Exception e) {
            log.error("Search Agent 执行异常: {}", queries, e);
            sink.sendError(ErrorCode.AGENT_EXECUTION_ERROR.getCode(),
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
                if (isPolluted(queries.get(i), results)) {
                    log.warn("检索词结果疑似 Bing 污染，丢弃: query={}", queries.get(i));
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
                return uniqueByUrl(variants);
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

    /**
     * Bing 污染快检：cn.bing.com 中文版对含"微服务"等歧义中文词的查询会返回随机无关泛化结果
     * （政治新闻/Messenger 等），个别英文技术词（如 microservices）也可能漂移返回垃圾内容；
     * www.bing.com 双域合并后污染率被稀释。对 title+snippet 做关键词命中率检查，
     * 零命中结果占比 ≥ 80% 判定污染。应用于全部检索词组（含英文词——结果页几乎总含字面词，
     * 不误判；测试用的人工短词除外）。
     *
     * 关键词词集：query 按空白拆分的词（≥2 字符，小写）+ EN_ANCHOR_MAP 命中歧义词的英文映射
     * （防英文站结果 title 无中文词被误杀）。不能用 TextUtils.extractKeywords——中文无停用词
     * 分隔时整段粘连成一个长关键词，contains 必 0 命中，所有中文查询都会误判污染。
     */
    private static boolean isPolluted(String query, List<SearchResult> results) {
        if (query == null || results == null || results.isEmpty()) {
            return false;
        }
        List<String> keywords = pollutionKeywords(query);
        if (keywords.isEmpty()) {
            return false;
        }
        long zeroHit = results.stream()
                .filter(r -> !hitsAnyKeyword(r, keywords))
                .count();
        return zeroHit * 10 >= results.size() * 8;
    }

    private static List<String> pollutionKeywords(String query) {
        List<String> keywords = new ArrayList<>();
        for (String tok : query.toLowerCase().split("\\s+")) {
            if (tok.length() >= 2 && !keywords.contains(tok)) {
                keywords.add(tok);
            }
        }
        EN_ANCHOR_MAP.entrySet().stream()
                .sorted(java.util.Comparator.comparingInt(e -> -e.getKey().length()))
                .forEach(e -> {
                    if (query.contains(e.getKey())) {
                        for (String word : e.getValue().split("\\s+")) {
                            if (!keywords.contains(word)) {
                                keywords.add(word);
                            }
                        }
                    }
                });
        return keywords;
    }

    private static boolean hitsAnyKeyword(SearchResult r, List<String> keywords) {
        String text = ((r.title() == null ? "" : r.title()) + " "
                + (r.snippet() == null ? "" : r.snippet())).toLowerCase();
        return keywords.stream().anyMatch(text::contains);
    }

    /**
     * 低质兜底：从 raw 结果中筛选"逐条命中"主词词集的相关条目（污染结果不兜底，防胡编），
     * 以搜索摘要为内容构造 degraded 评分项，截断到 maxResults。
     */
    private List<ScoredResource> buildDegradedResults(String query, List<SearchResult> raw,
                                                      int maxResults) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        List<String> keywords = pollutionKeywords(query);
        List<ScoredResource> degraded = new ArrayList<>();
        for (SearchResult r : raw) {
            if (r.url() == null || r.url().isBlank() || !hitsAnyKeyword(r, keywords)) {
                continue;
            }
            String content = (r.snippet() == null || r.snippet().isBlank()) ? r.title() : r.snippet();
            if (content == null || content.isBlank()) {
                continue;
            }
            degraded.add(searchTools.degradedResource(r.url(), r.title(), content));
        }
        return degraded.size() > maxResults ? degraded.subList(0, maxResults) : degraded;
    }
}
