package com.dart.agent.search;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Bing 搜索客户端 — 直连 cn.bing.com（3 页，冷却态 1 页）+ www.bing.com（第 1 页兜底），
 * 单词内页串行抓取 + 全局出站并发 ≤2（多词×多页同打实测触发限流，16 并发 1s 内全失败）。
 * 连续失败触发限流冷却：冷却期仅试探单页，成功即恢复；单页失败重试 1 次（抖动兜底）。
 * 国际版兜底：cn.bing.com 对个别英文技术词（如 microservices）会返回随机无关结果，
 * www.bing.com 对同一查询返回正常内容。
 */
public class BingSearchClient {

    private static final Logger log = LoggerFactory.getLogger(BingSearchClient.class);

    private static final String CN_BING = "https://cn.bing.com/search";
    private static final String WWW_BING = "https://www.bing.com/search";
    private static final int PAGES = 3;
    // 国际版只抓第 1 页：正常场景 cn 已够量，www 仅兜底污染词，1 页 10 条足量且控制请求总量
    private static final int WWW_PAGES = 1;

    /** 全局出站并发上限（静态、跨实例）：请求高峰期（如搜索词并发）限流主因是多页同发。 */
    private static final java.util.concurrent.Semaphore OUTBOUND = new java.util.concurrent.Semaphore(2);
    /** 进冷却的滚动失败阈值（60s 窗口）与冷却时长：冷却期降载为单页试探。 */
    private static final int FAILURE_TRIGGER = 6;
    private static final long COOLDOWN_MS = 60_000;
    private static final long RETRY_DELAY_MS = 500;

    private static final java.util.concurrent.atomic.AtomicInteger recentFailures =
            new java.util.concurrent.atomic.AtomicInteger();
    private static final java.util.concurrent.atomic.AtomicLong lastFailAt =
            new java.util.concurrent.atomic.AtomicLong();

    /** 限流冷却态（连续失败触发后 60s 内为真）：期间只探 cn 第 1 页。 */
    static boolean isCooldown() {
        return System.currentTimeMillis() - lastFailAt.get() < COOLDOWN_MS;
    }

    /** 测试隔离：重置限流计数（最近失败窗口）。 */
    static void resetRateState() {
        recentFailures.set(0);
        lastFailAt.set(0);
    }

    public List<SearchResult> search(String query, int maxResults) {
        Set<String> seen = new LinkedHashSet<>();
        List<SearchResult> all = new ArrayList<>();
        boolean cooldown = isCooldown();
        int cnPages = cooldown ? 1 : PAGES;
        for (int page = 0; page < cnPages; page++) {
            if (!fetchOne(CN_BING, query, page, seen, all)) {
                break; // 同域连续失败：停止后续页抓取（降载），避免雪崩
            }
        }
        if (!cooldown) {
            fetchOne(WWW_BING, query, 0, seen, all);
        }
        log.info("Bing 搜索完成: query={}, 总抓取={}, 去重后={}" + (cooldown ? "（限流冷却单页）" : ""),
                query, all.size(), Math.min(all.size(), maxResults));
        return all.size() > maxResults ? all.subList(0, maxResults) : all;
    }

    /** 单页抓取（串行）→ 去重收集；失败记限流计数；首页失败额外重试 1 次；成功重置计数。 */
    private boolean fetchOne(String domain, String query, int page,
                             Set<String> seen, List<SearchResult> all) {
        try {
            OUTBOUND.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return true;
        }
        try {
            List<SearchResult> pageResults = fetchPage(domain, query, page);
            for (SearchResult r : pageResults) {
                String normalized = normalizeUrl(r.url());
                if (seen.add(normalized)) {
                    all.add(r);
                }
            }
            recentFailures.set(0);
            lastFailAt.set(0);
            return true;
        } catch (Exception e) {
            log.warn("Bing 第{}页抓取失败: domain={}, query={} ({})", page + 1, domain, query, e);
            recordFailure();
            if (page == 0 && !isCooldown()) {
                // 抖动兜底重试（限流场景大概率仍失败，冷却计数已累计）
                try {
                    Thread.sleep(RETRY_DELAY_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return false;
                }
                try {
                    List<SearchResult> retry = fetchPage(domain, query, page);
                    for (SearchResult r : retry) {
                        String normalized = normalizeUrl(r.url());
                        if (seen.add(normalized)) {
                            all.add(r);
                        }
                    }
                    recentFailures.set(0);
                    lastFailAt.set(0);
                    return true;
                } catch (Exception retryEx) {
                    log.warn("Bing 第{}页重试失败: domain={}, query={}", page + 1, domain, query);
                    recordFailure();
                }
            }
            return false;
        } finally {
            OUTBOUND.release();
        }
    }

    private static void recordFailure() {
        lastFailAt.set(System.currentTimeMillis());
        int count = recentFailures.incrementAndGet();
        if (count == FAILURE_TRIGGER) {
            log.warn("Bing 连续失败 {} 次，进入限流冷却（{}ms 内仅试探单页）", count, COOLDOWN_MS);
        }
    }

    List<SearchResult> fetchPage(String domain, String query, int page) throws Exception {
        int first = page * 10 + 1;
        Document doc = Jsoup.connect(domain)
                .data("q", query)
                .data("first", String.valueOf(first))
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120.0.0.0 Safari/537.36")
                // 缺 Accept-Language/Accept 时 Bing 返回降级/无关结果（实测：仅 UA 时 www 对
                // microservices 返回百度知道垃圾，带完整 header 返回 GeeksforGeeks 等正常内容）
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .timeout(10000)
                .get();

        List<SearchResult> results = new ArrayList<>();
        for (Element li : doc.select("li.b_algo")) {
            Element link = li.selectFirst("h2 a");
            Element snippet = li.selectFirst(".b_caption p, .b_lineclamp2, .b_algoSlug");
            if (link == null) continue;

            String url = link.attr("href");
            String title = link.text();
            String desc = snippet != null ? snippet.text() : "";
            if (!url.isEmpty() && !title.isEmpty()) {
                results.add(new SearchResult(url, title, desc));
            }
        }
        return results;
    }

    private static String normalizeUrl(String url) {
        String s = url.strip();
        if (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        int idx = s.indexOf('?');
        return idx > 0 ? s.substring(0, idx) : s;
    }
}
