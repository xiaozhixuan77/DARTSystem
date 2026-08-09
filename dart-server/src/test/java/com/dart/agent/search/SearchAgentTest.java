package com.dart.agent.search;

import com.dart.agent.scoring.PageContent;
import com.dart.agent.scoring.ScoredResource;
import com.dart.util.SseUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SearchAgentTest {

    @Mock
    private SearchTools searchTools;

    private SseEmitter emitter;
    private SearchAgent searchAgent;

    @BeforeEach
    void setUp() {
        emitter = spy(new SseEmitter(60_000L));
        searchAgent = new SearchAgent(searchTools);
    }

    @Test
    void execute_normalFlow_returnsTopResults() throws IOException {
        List<SearchResult> searchResults = IntStream.range(0, 10)
                .mapToObj(i -> new SearchResult("https://site" + i + ".com", "Title " + i, "Snippet " + i))
                .toList();
        when(searchTools.searchWeb(anyString(), anyInt())).thenReturn(searchResults);
        when(searchTools.fetchPages(anyList())).thenAnswer(inv -> {
            List<String> urls = inv.getArgument(0);
            return urls.stream().map(u -> new PageContent(u, "T", "content ".repeat(100), 1000, 2, true, false, 2026)).toList();
        });
        when(searchTools.scoreResources(anyString(), anyList())).thenAnswer(inv -> {
            @SuppressWarnings("unchecked")
            List<PageContent> pages = inv.getArgument(1);
            return pages.stream()
                    .map(p -> new ScoredResource(p.getUrl(), p.getTitle(), p.getContent(), 5, 5, 5, 5, 5.0))
                    .toList();
        });

        List<ScoredResource> result = searchAgent.execute(List.of("Spring Boot"), 5, emitter);
        assertThat(result).isNotEmpty();
        assertThat(result.size()).isLessThanOrEqualTo(5);
        verify(searchTools).searchWeb(eq("Spring Boot"), anyInt());
        verify(searchTools).fetchPages(anyList());
        verify(searchTools).scoreResources(eq("Spring Boot"), anyList());
        verify(emitter, atLeastOnce()).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    void execute_searchReturnsEmpty_returnsEmpty() {
        when(searchTools.searchWeb(anyString(), anyInt())).thenReturn(List.of());

        List<ScoredResource> result = searchAgent.execute(List.of("obscure query"), 5, emitter);
        assertThat(result).isEmpty();
        verify(searchTools, never()).fetchPages(anyList());
    }

    @Test
    void execute_allFetchFail_returnsEmpty() {
        when(searchTools.searchWeb(anyString(), anyInt()))
                .thenReturn(List.of(new SearchResult("https://fail.com", "Fail", "snippet")));
        when(searchTools.fetchPages(anyList())).thenReturn(List.of());

        List<ScoredResource> result = searchAgent.execute(List.of("query"), 5, emitter);
        assertThat(result).isEmpty();
    }

    @Test
    void execute_searchToolsException_returnsEmpty() {
        when(searchTools.searchWeb(anyString(), anyInt())).thenThrow(new RuntimeException("network error"));

        List<ScoredResource> result = searchAgent.execute(List.of("query"), 5, emitter);
        assertThat(result).isEmpty();
    }

    @Test
    void execute_deduplicatesUrls() {
        // 10 results but only 5 unique URLs
        List<SearchResult> dups = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            dups.add(new SearchResult("https://unique" + (i % 5) + ".com", "T" + i, "snippet"));
        }
        when(searchTools.searchWeb(anyString(), anyInt())).thenReturn(dups);
        when(searchTools.fetchPages(anyList())).thenAnswer(inv -> {
            @SuppressWarnings("unchecked")
            List<String> urls = inv.getArgument(0);
            return urls.stream().map(u -> new PageContent(u, "T", "c ".repeat(100), 1000, 1, false, false, 2026)).toList();
        });
        when(searchTools.scoreResources(anyString(), anyList())).thenAnswer(inv -> {
            @SuppressWarnings("unchecked")
            List<PageContent> pages = inv.getArgument(1);
            return pages.stream().map(p -> new ScoredResource(p.getUrl(), p.getTitle(), p.getContent(), 5, 4, 4, 4, 4.0)).toList();
        });

        List<ScoredResource> result = searchAgent.execute(List.of("query"), 8, emitter);
        // 5 unique URLs after dedup
        assertThat(result.size()).isLessThanOrEqualTo(5);
        verify(searchTools).fetchPages(argThat(urls -> urls.size() <= 5));
    }

    @Test
    void execute_returnsAtMostMaxResults() {
        List<SearchResult> searchResults = IntStream.range(0, 15)
                .mapToObj(i -> new SearchResult("https://site" + i + ".com", "T" + i, "S" + i))
                .toList();
        when(searchTools.searchWeb(anyString(), anyInt())).thenReturn(searchResults);
        when(searchTools.fetchPages(anyList())).thenAnswer(inv -> {
            @SuppressWarnings("unchecked")
            List<String> urls = inv.getArgument(0);
            return urls.stream().map(u -> new PageContent(u, "T", "c ".repeat(100), 1000, 1, false, false, 2026)).toList();
        });
        when(searchTools.scoreResources(anyString(), anyList())).thenAnswer(inv -> {
            @SuppressWarnings("unchecked")
            List<PageContent> pages = inv.getArgument(1);
            return pages.stream().map(p -> new ScoredResource(p.getUrl(), p.getTitle(), p.getContent(), 5, 4, 4, 4, 4.15)).toList();
        });

        List<ScoredResource> result = searchAgent.execute(List.of("query"), 3, emitter);
        assertThat(result.size()).isLessThanOrEqualTo(3);
    }

    @Test
    void execute_multipleQueries_mergesAndDeduplicatesUrls() {
        // 两组词各返回部分结果，其中 b.com 重复
        when(searchTools.searchWeb(anyString(), anyInt())).thenAnswer(inv -> {
            String q = inv.getArgument(0);
            if (q.equals("Spring Boot 配置")) {
                return List.of(new SearchResult("https://a.com", "A", "s"),
                        new SearchResult("https://b.com", "B", "s"));
            }
            if (q.equals("Spring Boot datasource 教程")) {
                return List.of(new SearchResult("https://b.com", "B", "s"),
                        new SearchResult("https://c.com", "C", "s"));
            }
            return List.of();
        });
        when(searchTools.fetchPages(anyList())).thenAnswer(inv -> {
            @SuppressWarnings("unchecked")
            List<String> urls = inv.getArgument(0);
            return urls.stream().map(u -> new PageContent(u, "T", "c ".repeat(100), 1000, 1, false, false, 2026)).toList();
        });
        when(searchTools.scoreResources(anyString(), anyList())).thenAnswer(inv -> {
            @SuppressWarnings("unchecked")
            List<PageContent> pages = inv.getArgument(1);
            return pages.stream().map(p -> new ScoredResource(p.getUrl(), p.getTitle(), p.getContent(), 5, 4, 4, 4, 4.0)).toList();
        });

        searchAgent.execute(List.of("Spring Boot 配置", "Spring Boot datasource 教程"), 5, emitter);

        // 两组词都被搜索，合并结果后 URL 去重：a、b、c 各抓一次
        verify(searchTools, times(2)).searchWeb(anyString(), anyInt());
        verify(searchTools).searchWeb(eq("Spring Boot 配置"), anyInt());
        verify(searchTools).searchWeb(eq("Spring Boot datasource 教程"), anyInt());
        verify(searchTools).fetchPages(argThat(urls -> urls.equals(List.of("https://a.com", "https://b.com", "https://c.com"))));
    }

    @Test
    void execute_sendsStatusEvents() throws IOException {
        when(searchTools.searchWeb(anyString(), anyInt()))
                .thenReturn(List.of(new SearchResult("https://site.com", "Title", "Snippet")));
        when(searchTools.fetchPages(anyList())).thenAnswer(inv -> {
            @SuppressWarnings("unchecked")
            List<String> urls = inv.getArgument(0);
            return urls.stream().map(u -> new PageContent(u, "T", "c ".repeat(100), 1000, 1, false, false, 2026)).toList();
        });
        when(searchTools.scoreResources(anyString(), anyList())).thenAnswer(inv -> {
            @SuppressWarnings("unchecked")
            List<PageContent> pages = inv.getArgument(1);
            return pages.stream().map(p -> new ScoredResource(p.getUrl(), p.getTitle(), p.getContent(), 5, 4, 3, 4, 4.0)).toList();
        });

        searchAgent.execute(List.of("query"), 5, emitter);
        verify(emitter, atLeast(3)).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    void execute_allBelowQualityThreshold_returnsEmpty() {
        when(searchTools.searchWeb(anyString(), anyInt()))
                .thenReturn(List.of(new SearchResult("https://low.com", "Low", "snippet")));
        when(searchTools.fetchPages(anyList())).thenAnswer(inv -> {
            @SuppressWarnings("unchecked")
            List<String> urls = inv.getArgument(0);
            return urls.stream().map(u -> new PageContent(u, "T", "c ", 100, 0, false, false, 2020)).toList();
        });
        // scoreResources already filters below threshold → empty
        when(searchTools.scoreResources(anyString(), anyList())).thenReturn(List.of());

        List<ScoredResource> result = searchAgent.execute(List.of("query"), 5, emitter);
        assertThat(result).isEmpty();
    }

    @Test
    void execute_topResultsSortedDescending() {
        List<SearchResult> searchResults = IntStream.range(0, 5)
                .mapToObj(i -> new SearchResult("https://site" + i + ".com", "T" + i, "S" + i))
                .toList();
        when(searchTools.searchWeb(anyString(), anyInt())).thenReturn(searchResults);
        when(searchTools.fetchPages(anyList())).thenAnswer(inv -> {
            @SuppressWarnings("unchecked")
            List<String> urls = inv.getArgument(0);
            return urls.stream().map(u -> new PageContent(u, "T", "c ".repeat(100), 1000, 1, false, false, 2026)).toList();
        });
        when(searchTools.scoreResources(anyString(), anyList())).thenAnswer(inv -> {
            @SuppressWarnings("unchecked")
            List<PageContent> pages = inv.getArgument(1);
            List<ScoredResource> scored = new ArrayList<>();
            int i = 0;
            for (PageContent p : pages) {
                double s = 4.0 - i * 0.2;
                scored.add(new ScoredResource(p.getUrl(), p.getTitle(), p.getContent(), 5, 4, 4, 4, s));
                i++;
            }
            scored.sort((a, b) -> Double.compare(b.getWeightedScore(), a.getWeightedScore()));
            return scored;
        });

        List<ScoredResource> result = searchAgent.execute(List.of("query"), 5, emitter);
        if (result.size() >= 2) {
            assertThat(result.get(0).getWeightedScore()).isGreaterThanOrEqualTo(result.get(1).getWeightedScore());
        }
    }
}
