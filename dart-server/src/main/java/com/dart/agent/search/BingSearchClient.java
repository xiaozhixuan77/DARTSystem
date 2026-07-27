package com.dart.agent.search;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Bing 搜索客户端 — 直连 cn.bing.com，解析 HTML 搜索结果。
 * 替代 SearXNG，无需代理，适用于国内网络环境。
 */
public class BingSearchClient {

    private static final Logger log = LoggerFactory.getLogger(BingSearchClient.class);

    private static final String BING_URL = "https://cn.bing.com/search";

    public List<SearchResult> search(String query, int maxResults) {
        try {
            Document doc = Jsoup.connect(BING_URL)
                    .data("q", query)
                    .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120.0.0.0 Safari/537.36")
                    .timeout(15000)
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
                    if (results.size() >= maxResults) break;
                }
            }
            log.info("Bing 搜索成功: query={}, results={}", query, results.size());
            return results;
        } catch (Exception e) {
            log.error("Bing 搜索失败: query={}", query, e);
            return List.of();
        }
    }
}
