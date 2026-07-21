package com.dart.agent.search;

import com.dart.agent.scoring.PageContent;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Year;
import java.util.regex.Pattern;

/**
 * Jsoup 页面抓取与正文提取。
 */
public class PageFetcher {

    private static final Logger log = LoggerFactory.getLogger(PageFetcher.class);

    private static final Pattern CODE_FENCE = Pattern.compile("```");
    private static final Pattern DIAGRAM_KEYWORDS = Pattern.compile(
            "\\b(diagram|archite[ck]ture|flowchart|sequence|component|class diagram|时序图|架构图|流程图)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern EXERCISE_KEYWORDS = Pattern.compile(
            "\\b(exercise|practice|homework|assignment|lab|实验|练习|作业|动手|try it|example\\s*\\d+)\\b",
            Pattern.CASE_INSENSITIVE);

    private final int timeoutMs;

    public PageFetcher(int timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    public PageContent fetch(String url) {
        Document doc = doConnect(url);
        String title = extractTitle(doc);
        String content = extractContent(doc);
        int wordCount = countWords(content);
        int codeBlockCount = countCodeBlocks(content);
        boolean hasDiagram = DIAGRAM_KEYWORDS.matcher(content).find();
        boolean hasExercise = EXERCISE_KEYWORDS.matcher(content).find();
        Integer year = extractYear(content);
        return new PageContent(url, title, content, wordCount, codeBlockCount, hasDiagram, hasExercise, year);
    }

    protected Document doConnect(String url) {
        validateUrl(url);
        try {
            return Jsoup.connect(url)
                    .timeout(timeoutMs)
                    .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) DART/1.0")
                    .get();
        } catch (Exception e) {
            log.warn("页面抓取失败: {}", url, e);
            throw new RuntimeException("页面抓取失败: " + url, e);
        }
    }

    private void validateUrl(String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("URL 不能为空");
        }
        String lower = url.toLowerCase();
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            throw new IllegalArgumentException("仅支持 HTTP/HTTPS 协议");
        }
        try {
            java.net.URI uri = java.net.URI.create(url);
            String host = uri.getHost();
            if (host == null) {
                throw new IllegalArgumentException("无法解析主机名");
            }
            if (isPrivateAddress(host)) {
                throw new IllegalArgumentException("不允许访问内网地址: " + host);
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("无效的 URL: " + url, e);
        }
    }

    private boolean isPrivateAddress(String host) {
        if (host.equals("localhost") || host.equals("127.0.0.1") || host.equals("[::1]")
                || host.startsWith("192.168.") || host.startsWith("10.")
                || host.startsWith("172.16.") || host.startsWith("172.17.")
                || host.startsWith("172.18.") || host.startsWith("172.19.")
                || host.startsWith("172.20.") || host.startsWith("172.21.")
                || host.startsWith("172.22.") || host.startsWith("172.23.")
                || host.startsWith("172.24.") || host.startsWith("172.25.")
                || host.startsWith("172.26.") || host.startsWith("172.27.")
                || host.startsWith("172.28.") || host.startsWith("172.29.")
                || host.startsWith("172.30.") || host.startsWith("172.31.")
                || host.startsWith("169.254.") || host.startsWith("0.")
                || host.endsWith(".local") || host.endsWith(".internal")) {
            return true;
        }
        return false;
    }

    private String extractTitle(Document doc) {
        String title = doc.title();
        if (title != null && !title.isBlank()) {
            return title.trim();
        }
        Elements h1 = doc.select("h1");
        if (!h1.isEmpty()) {
            return h1.first().text().trim();
        }
        return "";
    }

    private String extractContent(Document doc) {
        // 移除不需要的元素
        for (String selector : new String[]{"script", "style", "nav", "footer", "header",
                "aside", ".sidebar", ".nav", ".footer", ".header", ".ad", ".advertisement",
                ".cookie", "#cookie", ".popup", ".modal"}) {
            doc.select(selector).remove();
        }

        Element body = doc.body();
        if (body == null) {
            return "";
        }

        // 提取文章主体
        Element article = doc.selectFirst("article");
        Element main = doc.selectFirst("main");
        Element content = doc.selectFirst(".content, .post, .article, .markdown-body, .prose");
        Element target = article != null ? article
                : main != null ? main
                : content != null ? content
                : body;

        return target.text();
    }

    private int countWords(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        return text.split("\\s+").length;
    }

    private int countCodeBlocks(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        int count = 0;
        java.util.regex.Matcher m = CODE_FENCE.matcher(text);
        while (m.find()) {
            count++;
        }
        return count / 2; // 每对 ``` 算一个代码块
    }

    private Integer extractYear(String content) {
        Pattern yearPattern = Pattern.compile("\\b(20\\d{2})\\b");
        java.util.regex.Matcher m = yearPattern.matcher(content);
        int latest = 0;
        while (m.find()) {
            int y = Integer.parseInt(m.group(1));
            if (y > latest && y <= Year.now().getValue()) {
                latest = y;
            }
        }
        return latest > 0 ? latest : null;
    }
}
