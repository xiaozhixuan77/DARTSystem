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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Bing 搜索客户端 — 直连 cn.bing.com（3 页）+ www.bing.com（第 1 页兜底）虚拟线程并行抓取，
 * 解析 HTML 搜索结果。国际版兜底：cn.bing.com 对个别英文技术词（如 microservices）会返回
 * 随机无关结果（Bing 侧行为漂移），www.bing.com 对同一查询返回正常内容。
 */
public class BingSearchClient {

    private static final Logger log = LoggerFactory.getLogger(BingSearchClient.class);

    private static final String CN_BING = "https://cn.bing.com/search";
    private static final String WWW_BING = "https://www.bing.com/search";
    private static final int PAGES = 3;
    // 国际版只抓第 1 页：正常场景 cn 已够量，www 仅兜底污染词，1 页 10 条足量且控制请求总量
    private static final int WWW_PAGES = 1;

    public List<SearchResult> search(String query, int maxResults) {
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            List<Future<List<SearchResult>>> futures = new ArrayList<>(PAGES + WWW_PAGES);
            for (int page = 0; page < PAGES; page++) {
                futures.add(submitPage(executor, CN_BING, query, page));
            }
            for (int page = 0; page < WWW_PAGES; page++) {
                futures.add(submitPage(executor, WWW_BING, query, page));
            }

            Set<String> seen = new LinkedHashSet<>();
            List<SearchResult> all = new ArrayList<>();
            for (Future<List<SearchResult>> future : futures) {
                try {
                    List<SearchResult> pageResults = future.get();
                    for (SearchResult r : pageResults) {
                        String normalized = normalizeUrl(r.url());
                        if (seen.add(normalized)) {
                            all.add(r);
                        }
                    }
                } catch (Exception e) {
                    log.warn("Bing 页面结果获取异常: query={}", query, e);
                }
            }

            log.info("Bing 搜索完成: query={}, 总抓取={}, 去重后={}", query, all.size(), Math.min(all.size(), maxResults));
            return all.size() > maxResults ? all.subList(0, maxResults) : all;
        } finally {
            executor.shutdownNow();
        }
    }

    private Future<List<SearchResult>> submitPage(ExecutorService executor, String domain,
                                                  String query, int page) {
        return executor.submit(() -> {
            try {
                return fetchPage(domain, query, page);
            } catch (Exception e) {
                log.warn("Bing 第{}页抓取失败: domain={}, query={}", page + 1, domain, query);
                return List.of();
            }
        });
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
