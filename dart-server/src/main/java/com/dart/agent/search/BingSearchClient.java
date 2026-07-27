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
 * Bing 搜索客户端 — 直连 cn.bing.com，多页抓取，解析 HTML 搜索结果。
 * 替代 SearXNG，无需代理，适用于国内网络环境。
 */
public class BingSearchClient {

    private static final Logger log = LoggerFactory.getLogger(BingSearchClient.class);

    private static final String BING_URL = "https://cn.bing.com/search";
    private static final int PAGES = 3;

    public List<SearchResult> search(String query, int maxResults) {
        Set<String> seen = new LinkedHashSet<>();
        List<SearchResult> all = new ArrayList<>();

        for (int page = 0; page < PAGES; page++) {
            try {
                List<SearchResult> pageResults = fetchPage(query, page);
                for (SearchResult r : pageResults) {
                    String normalized = normalizeUrl(r.url());
                    if (seen.add(normalized)) {
                        all.add(r);
                    }
                }
                if (all.size() >= maxResults) break;
            } catch (Exception e) {
                log.warn("Bing 第{}页抓取失败: query={}", page + 1, query);
            }
        }

        log.info("Bing 搜索完成: query={}, 总抓取={}, 去重后={}", query, all.size(), Math.min(all.size(), maxResults));
        return all.size() > maxResults ? all.subList(0, maxResults) : all;
    }

    private List<SearchResult> fetchPage(String query, int page) throws Exception {
        int first = page * 10 + 1;
        Document doc = Jsoup.connect(BING_URL)
                .data("q", query)
                .data("first", String.valueOf(first))
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120.0.0.0 Safari/537.36")
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
