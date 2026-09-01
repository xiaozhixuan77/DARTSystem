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

    private static final int MAX_ATTEMPTS = 2;
    private static final long RETRY_DELAY_MS = 500;
    private static final int MAX_EXTRACTED_TEXT_CHARS = 200_000;

    /** 完整 Chrome UA（半伪造 UA（DART/1.0 尾巴）会被 CSDN/知乎等反爬识别 → 403/521，抓取成功率暴跌）。 */
    static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36";

    /** 浏览器标准请求头：Accept/Accept-Language 必带，Referer 用 Bing 结果页（贴合浏览器点击跳转场景）。 */
    static java.util.Map<String, String> browserHeaders() {
        return java.util.Map.of(
                "Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,"
                        + "image/webp,*/*;q=0.8",
                "Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8",
                "Referer", "https://www.bing.com/");
    }
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
        int codeBlockCount = countCodeBlocks(doc);
        boolean hasDiagram = DIAGRAM_KEYWORDS.matcher(content).find();
        boolean hasExercise = EXERCISE_KEYWORDS.matcher(content).find();
        Integer year = extractYear(content);
        return new PageContent(url, title, content, wordCount, codeBlockCount, hasDiagram, hasExercise, year);
    }

    protected Document doConnect(String url) {
        validateUrl(url);
        RuntimeException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return connectOnce(url);
            } catch (RuntimeException e) {
                last = e;
                // 连接层失败（域名不可达/连接被拒/TLS 失败）：已尝试 https→http 降级，不再重试浪费等待
                if (isConnectLayerFailure(e)) {
                    break;
                }
                if (attempt < MAX_ATTEMPTS) {
                    try {
                        Thread.sleep(RETRY_DELAY_MS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
        log.warn("页面抓取失败: {}", url, last);
        throw new RuntimeException("页面抓取失败: " + url, last);
    }

    /**
     * 单次连接尝试（含 https→http 降级；重试循环的原子单位，测试可覆写模拟失败/成功）。
     * 官网直连不可达是常态（如 go.dev），https 连接层失败时降级尝试 http 版本（短超时）。
     */
    protected Document connectOnce(String url) {
        try {
            return connectRaw(url, timeoutMs);
        } catch (RuntimeException e) {
            if (url.startsWith("https://") && isConnectLayerFailure(e)) {
                String httpUrl = "http://" + url.substring("https://".length());
                try {
                    return connectRaw(httpUrl, timeoutMs / 2);
                } catch (RuntimeException httpE) {
                    e.addSuppressed(httpE);
                }
            }
            throw e;
        }
    }

    /**
     * 单次 HTTP 连接（降级链与重试的原子单位，测试可覆写）。
     */
    protected Document connectRaw(String url, int connectTimeoutMs) {
        try {
            var conn = Jsoup.connect(url)
                    .timeout(connectTimeoutMs)
                    .userAgent(USER_AGENT);
            browserHeaders().forEach(conn::header);
            return conn.get();
        } catch (java.io.IOException e) {
            throw new RuntimeException("连接失败: " + url, e);
        }
    }

    /**
     * 连接层失败判定：连接超时/被拒/无路由/TLS 握手失败（区别于已建连后的读超时）。
     */
    private static boolean isConnectLayerFailure(Throwable t) {
        while (t != null) {
            String msg = t.getMessage() == null ? "" : t.getMessage();
            if (t instanceof java.net.ConnectException
                    || t instanceof java.net.NoRouteToHostException
                    || t instanceof javax.net.ssl.SSLException
                    || msg.contains("Connect timed out")
                    || msg.contains("Connection refused")
                    || msg.contains("Unable to connect")) {
                return true;
            }
            t = t.getCause();
        }
        return false;
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

        // 超大页面防护：截断到上限，防 12 URL 并行抓取时的内存峰值
        String text = target.text();
        return text.length() > MAX_EXTRACTED_TEXT_CHARS
                ? text.substring(0, MAX_EXTRACTED_TEXT_CHARS)
                : text;
    }

    private int countWords(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        return text.split("\\s+").length;
    }

    /** 代码块统计改对 HTML 原文：<pre>/<code> 元素（此前对纯文本数 ``` 恒为 0，深度评分低估代码页） */
    private int countCodeBlocks(Document doc) {
        return doc.select("pre, code").size();
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
