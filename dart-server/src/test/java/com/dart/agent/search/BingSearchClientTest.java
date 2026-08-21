package com.dart.agent.search;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BingSearchClient 并行搜索测试 — 通过子类重写 fetchPage 注入固定分页结果，
 * 验证双域（cn 3 页 + www 1 页）并行合并、跨页/跨域去重、maxResults 截断与单页失败容错。
 */
class BingSearchClientTest {

    private FakeBingClient client;

    @BeforeEach
    void setUp() {
        client = new FakeBingClient();
    }

    private static class FakeBingClient extends BingSearchClient {
        private final List<List<SearchResult>> cnPages = new ArrayList<>();
        private final List<List<SearchResult>> wwwPages = new ArrayList<>();
        private final AtomicInteger active = new AtomicInteger(0);
        private final AtomicInteger maxConcurrent = new AtomicInteger(0);
        private final AtomicInteger fetchCount = new AtomicInteger(0);
        private boolean failSecondPage;

        FakeBingClient withCnPages(List<List<SearchResult>> pages) {
            this.cnPages.clear();
            this.cnPages.addAll(pages);
            return this;
        }

        FakeBingClient withWwwPages(List<List<SearchResult>> pages) {
            this.wwwPages.clear();
            this.wwwPages.addAll(pages);
            return this;
        }

        FakeBingClient failSecondPage() {
            this.failSecondPage = true;
            return this;
        }

        @Override
        List<SearchResult> fetchPage(String domain, String query, int page) {
            int cur = active.incrementAndGet();
            maxConcurrent.accumulateAndGet(cur, Math::max);
            fetchCount.incrementAndGet();
            try {
                // 短暂停留，让其他页有机会同时进入，从而观测到并行度
                Thread.sleep(50);
                if (failSecondPage && page == 1 && domain.contains("cn.bing.com")) {
                    return List.of();
                }
                List<List<SearchResult>> source = domain.contains("cn.bing.com") ? cnPages : wwwPages;
                return page < source.size() ? source.get(page) : List.of();
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
    void search_fetchesCnThreePagesAndWwwFirstPageInParallel() {
        client.withCnPages(List.of(
                page(1, "https://a.com/p", 3),
                page(4, "https://a.com/p", 3),
                page(7, "https://a.com/p", 2)));

        List<SearchResult> results = client.search("java", 10);

        assertThat(results).hasSize(8);
        // cn 3 页 + www 第 1 页共 4 次抓取，并行度 ≥ 2
        assertThat(client.fetchCount).hasValue(4);
        assertThat(client.maxConcurrent).hasValueGreaterThanOrEqualTo(2);
    }

    @Test
    void search_fetchesCnAndWwwDualDomain_mergesResults() {
        client.withCnPages(List.of(
                page(1, "https://cn.com/p", 3),
                page(4, "https://cn.com/p", 3),
                page(7, "https://cn.com/p", 2)))
                .withWwwPages(List.of(page(10, "https://www.com/p", 2)));

        List<SearchResult> results = client.search("java", 20);

        assertThat(results).hasSize(10);
        assertThat(results).extracting(SearchResult::url)
                .contains("https://cn.com/p1", "https://www.com/p10");
    }

    @Test
    void search_cnEmpty_wwwFallbackKept() {
        client.withCnPages(List.of(List.of(), List.of(), List.of()))
                .withWwwPages(List.of(page(10, "https://www.com/p", 2)));

        List<SearchResult> results = client.search("java", 10);

        assertThat(results).hasSize(2);
        assertThat(results.get(0).url()).startsWith("https://www.com/p");
    }

    @Test
    void search_deduplicatesAcrossDomains() {
        client.withCnPages(List.of(List.of(new SearchResult("https://a.com/doc", "T1", "S1")),
                List.of(), List.of()))
                .withWwwPages(List.of(List.of(new SearchResult("https://a.com/doc/", "T1b", "S1b"))));

        List<SearchResult> results = client.search("java", 10);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).url()).isEqualTo("https://a.com/doc");
    }

    @Test
    void search_deduplicatesAcrossPages() {
        // 第 2、3 页返回与第 1 页相同的 URL（含尾斜杠/query 差异）
        client.withCnPages(List.of(
                List.of(new SearchResult("https://a.com/doc", "T1", "S1")),
                List.of(new SearchResult("https://a.com/doc/", "T1b", "S1b")),
                List.of(new SearchResult("https://a.com/doc?x=1", "T1c", "S1c"))));

        List<SearchResult> results = client.search("java", 10);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).url()).isEqualTo("https://a.com/doc");
    }

    @Test
    void search_respectsMaxResults() {
        client.withCnPages(List.of(
                page(1, "https://a.com/p", 10),
                page(11, "https://a.com/p", 10),
                page(21, "https://a.com/p", 10)));

        List<SearchResult> results = client.search("java", 6);

        assertThat(results).hasSize(6);
    }

    @Test
    void search_pageFailure_continuesWithOtherPages() {
        client.withCnPages(List.of(
                page(1, "https://a.com/p", 3),
                page(4, "https://a.com/p", 3),
                page(7, "https://a.com/p", 2))).failSecondPage();

        List<SearchResult> results = client.search("java", 10);

        assertThat(results).hasSize(5);
    }

    @Test
    void search_returnsEmptyOnAllPagesFail() {
        client.withCnPages(List.of(
                List.of(), List.of(), List.of()));

        List<SearchResult> results = client.search("java", 10);

        assertThat(results).isEmpty();
    }
}
