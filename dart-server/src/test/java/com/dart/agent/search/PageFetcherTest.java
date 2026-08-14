package com.dart.agent.search;

import com.dart.agent.scoring.PageContent;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PageFetcherTest {

    private static Document doc(String title) {
        Document d = new Document("https://example.com");
        d.title(title);
        d.body().appendElement("article").text("正文内容 " + title);
        return d;
    }

    /** 可编程的 fetcher：覆写 connectOnce 模拟首次失败/连续失败。 */
    private static class ScriptableFetcher extends PageFetcher {
        private final java.util.function.IntFunction<Document> behavior;
        private int calls = 0;

        ScriptableFetcher(int timeoutMs, java.util.function.IntFunction<Document> behavior) {
            super(timeoutMs);
            this.behavior = behavior;
        }

        @Override
        protected Document connectOnce(String url) {
            calls++;
            return behavior.apply(calls);
        }

        int getCalls() {
            return calls;
        }
    }

    @Test
    void fetch_firstAttemptFails_retriesAndSucceeds() {
        ScriptableFetcher fetcher = new ScriptableFetcher(1000, calls ->
                calls == 1 ? throwTimeout() : doc("Go 官方文档"));

        PageContent page = fetcher.fetch("https://go.dev/doc/");

        assertThat(page.getUrl()).isEqualTo("https://go.dev/doc/");
        assertThat(page.getTitle()).isEqualTo("Go 官方文档");
        assertThat(fetcher.getCalls()).isEqualTo(2);
    }

    @Test
    void fetch_allAttemptsFail_throws() {
        ScriptableFetcher fetcher = new ScriptableFetcher(1000, calls -> throwTimeout());

        assertThatThrownBy(() -> fetcher.fetch("https://go.dev/doc/"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("页面抓取失败");
        assertThat(fetcher.getCalls()).isEqualTo(2);
    }

    @Test
    void fetch_invalidUrl_failsWithoutRetry() {
        ScriptableFetcher fetcher = new ScriptableFetcher(1000, calls -> doc("x"));

        assertThatThrownBy(() -> fetcher.fetch("javascript:alert(1)"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(fetcher.getCalls()).isZero();
    }

    @Test
    void fetch_privateAddress_failsWithoutRetry() {
        ScriptableFetcher fetcher = new ScriptableFetcher(1000, calls -> doc("x"));

        assertThatThrownBy(() -> fetcher.fetch("http://192.168.1.1/"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("内网地址");
        assertThat(fetcher.getCalls()).isZero();
    }

    private static Document throwTimeout() {
        throw new RuntimeException("Read timed out");
    }
}
