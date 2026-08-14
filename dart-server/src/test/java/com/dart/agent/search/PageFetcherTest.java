package com.dart.agent.search;

import com.dart.agent.scoring.PageContent;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PageFetcherTest {

    private static Document doc(String title) {
        Document d = new Document("https://example.com");
        d.title(title);
        d.body().appendElement("article").text("正文内容 " + title);
        return d;
    }

    /** 可编程的 fetcher：覆写 connectRaw 模拟失败/成功，记录每次调用。 */
    private static class ScriptableFetcher extends PageFetcher {
        private final java.util.function.IntFunction<Document> behavior;
        private final List<String> callUrls = new ArrayList<>();

        ScriptableFetcher(int timeoutMs, java.util.function.IntFunction<Document> behavior) {
            super(timeoutMs);
            this.behavior = behavior;
        }

        @Override
        protected Document connectRaw(String url, int timeoutMs) {
            callUrls.add(url);
            return behavior.apply(callUrls.size());
        }

        List<String> getCallUrls() {
            return callUrls;
        }
    }

    private static Document throwReadTimeout() {
        throw new RuntimeException("Read timed out");
    }

    private static Document throwConnectTimeout() {
        throw new RuntimeException("Connect timed out");
    }

    @Test
    void fetch_readTimeoutFirstAttempt_retriesAndSucceeds() {
        ScriptableFetcher fetcher = new ScriptableFetcher(1000, calls ->
                calls == 1 ? throwReadTimeout() : doc("Go 官方文档"));

        PageContent page = fetcher.fetch("https://go.dev/doc/");

        assertThat(page.getUrl()).isEqualTo("https://go.dev/doc/");
        assertThat(page.getTitle()).isEqualTo("Go 官方文档");
        assertThat(fetcher.getCallUrls()).hasSize(2);
    }

    @Test
    void fetch_readTimeoutAllAttempts_throws() {
        ScriptableFetcher fetcher = new ScriptableFetcher(1000, calls -> throwReadTimeout());

        assertThatThrownBy(() -> fetcher.fetch("https://go.dev/doc/"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("页面抓取失败");
        assertThat(fetcher.getCallUrls()).hasSize(2);
    }

    @Test
    void fetch_httpsConnectTimeout_fallsBackToHttp() {
        ScriptableFetcher fetcher = new ScriptableFetcher(1000, calls ->
                calls == 1 ? throwConnectTimeout() : doc("Go 文档（HTTP）"));

        PageContent page = fetcher.fetch("https://go.dev/doc/");

        assertThat(page.getTitle()).isEqualTo("Go 文档（HTTP）");
        // https 连接失败 → 降级 http 一次，成功即止（不重试）
        assertThat(fetcher.getCallUrls()).containsExactly("https://go.dev/doc/", "http://go.dev/doc/");
    }

    @Test
    void fetch_httpsConnectTimeout_httpFailsToo_noRetry() {
        ScriptableFetcher fetcher = new ScriptableFetcher(1000, calls -> throwConnectTimeout());

        assertThatThrownBy(() -> fetcher.fetch("https://go.dev/doc/"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("页面抓取失败");
        // https + http 各一次，连接层失败不触发第三次重试
        assertThat(fetcher.getCallUrls()).containsExactly("https://go.dev/doc/", "http://go.dev/doc/");
    }

    @Test
    void fetch_invalidUrl_failsWithoutConnect() {
        ScriptableFetcher fetcher = new ScriptableFetcher(1000, calls -> doc("x"));

        assertThatThrownBy(() -> fetcher.fetch("javascript:alert(1)"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(fetcher.getCallUrls()).isEmpty();
    }

    @Test
    void fetch_privateAddress_failsWithoutConnect() {
        ScriptableFetcher fetcher = new ScriptableFetcher(1000, calls -> doc("x"));

        assertThatThrownBy(() -> fetcher.fetch("http://192.168.1.1/"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("内网地址");
        assertThat(fetcher.getCallUrls()).isEmpty();
    }
}
