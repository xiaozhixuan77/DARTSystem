package com.dart.agent.search;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BingSearchClient 并行搜索测试 — 通过子类重写 fetchPage 注入固定分页结果，
 * 验证 3 页并行合并、跨页去重、maxResults 截断与单页失败容错。
 */
class BingSearchClientTest {

    private FakeBingClient client;

    @BeforeEach
    void setUp() {
        client = new FakeBingClient();
    }

    private static class FakeBingClient extends BingSearchClient {
        private final List<List<SearchResult>> pages = new ArrayList<>();
        private final AtomicInteger active = new AtomicInteger(0);
        private final AtomicInteger maxConcurrent = new AtomicInteger(0);
        private final AtomicInteger fetchCount = new AtomicInteger(0);
        private boolean failSecondPage;

        FakeBingClient withPages(List<List<SearchResult>> pages) {
            this.pages.clear();
            this.pages.addAll(pages);
            return this;
        }

        FakeBingClient failSecondPage() {
            this.failSecondPage = true;
            return this;
        }

        @Override
        List<SearchResult> fetchPage(String query, int page) {
            int cur = active.incrementAndGet();
            maxConcurrent.accumulateAndGet(cur, Math::max);
            fetchCount.incrementAndGet();
            try {
                // 短暂停留，让其他页有机会同时进入，从而观测到并行度
                Thread.sleep(50);
                if (failSecondPage && page == 1) {
                    return List.of();
                }
                return page < pages.size() ? pages.get(page) : List.of();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return List.of();
            } finally {
                active.decrementAndGet();
            }
        }
    }

    private static List<SearchResult> page(int offset, String baseUrl, int count) {
        List<SearchResult> results = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int n = offset + i;
            results.add(new SearchResult(baseUrl + n, "Title " + n, "Snippet " + n));
        }
        return results;
    }

    @Test
    void search_fetchesAllThreePagesInParallel() {
        client.withPages(List.of(
                page(1, "https://a.com/p", 3),
                page(4, "https://a.com/p", 3),
                page(7, "https://a.com/p", 2)));

        List<SearchResult> results = client.search("java", 10);

        assertThat(results).hasSize(8);
        assertThat(client.fetchCount).hasValue(3);
        assertThat(client.maxConcurrent).hasValueGreaterThanOrEqualTo(2);
    }

    @Test
    void search_deduplicatesAcrossPages() {
        // 第 2、3 页返回与第 1 页相同的 URL（含尾斜杠/query 差异）
        client.withPages(List.of(
                List.of(new SearchResult("https://a.com/doc", "T1", "S1")),
                List.of(new SearchResult("https://a.com/doc/", "T1b", "S1b")),
                List.of(new SearchResult("https://a.com/doc?x=1", "T1c", "S1c"))));

        List<SearchResult> results = client.search("java", 10);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).url()).isEqualTo("https://a.com/doc");
    }

    @Test
    void search_respectsMaxResults() {
        client.withPages(List.of(
                page(1, "https://a.com/p", 10),
                page(11, "https://a.com/p", 10),
                page(21, "https://a.com/p", 10)));

        List<SearchResult> results = client.search("java", 6);

        assertThat(results).hasSize(6);
    }

    @Test
    void search_pageFailure_continuesWithOtherPages() {
        client.withPages(List.of(
                page(1, "https://a.com/p", 3),
                page(4, "https://a.com/p", 3),
                page(7, "https://a.com/p", 2))).failSecondPage();

        List<SearchResult> results = client.search("java", 10);

        assertThat(results).hasSize(5);
    }

    @Test
    void search_returnsEmptyOnAllPagesFail() {
        client.withPages(List.of(
                List.of(), List.of(), List.of()));

        List<SearchResult> results = client.search("java", 10);

        assertThat(results).isEmpty();
    }
}
