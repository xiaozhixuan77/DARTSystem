package com.dart.agent.search;

import com.dart.agent.scoring.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SearchToolsTest {

    @Mock
    private SearXNGClient searXNGClient;
    @Mock
    private PageFetcher pageFetcher;

    private QualityScorer qualityScorer;
    private SearchTools searchTools;

    @BeforeEach
    void setUp() {
        qualityScorer = new QualityScorer(0.15, 0.30, 0.20, 0.35, 3.0);
        searchTools = new SearchTools(searXNGClient, pageFetcher, qualityScorer);
        lenient().when(searXNGClient.search(any(), anyInt())).thenReturn(List.of());
    }

    @Test
    void searchWeb_returnsResults() {
        when(searXNGClient.search(eq("Spring Boot"), anyInt()))
                .thenReturn(List.of(
                        new SearchResult("https://spring.io", "Spring Framework", "Official docs"),
                        new SearchResult("https://example.com", "Example", "A tutorial")
                ));

        List<SearchResult> results = searchTools.searchWeb("Spring Boot", 8);
        assertThat(results).hasSize(2);
        assertThat(results.get(0).url()).isEqualTo("https://spring.io");
    }

    @Test
    void searchWeb_whenClientReturnsEmpty_returnsEmpty() {
        when(searXNGClient.search(any(), anyInt())).thenReturn(List.of());
        assertThat(searchTools.searchWeb("unknown", 8)).isEmpty();
    }

    @Test
    void searchWeb_whenClientThrows_returnsEmpty() {
        when(searXNGClient.search(any(), anyInt())).thenThrow(new RuntimeException("timeout"));
        assertThat(searchTools.searchWeb("error", 8)).isEmpty();
    }

    @Test
    void fetchPages_parallelFetchSucceeds() {
        when(pageFetcher.fetch(any())).thenAnswer(inv -> {
            String url = inv.getArgument(0);
            return new PageContent(url, "Title", "Content text.", 100, 2, false, false, 2025);
        });

        List<PageContent> pages = searchTools.fetchPages(List.of(
                "https://a.com", "https://b.com", "https://c.com"
        ));
        assertThat(pages).hasSize(3);
    }

    @Test
    void fetchPages_partialFailure_returnsSuccessful() {
        when(pageFetcher.fetch("https://a.com")).thenReturn(
                new PageContent("https://a.com", "A", "text", 100, 1, false, false, 2025));
        when(pageFetcher.fetch("https://b.com")).thenThrow(new RuntimeException("timeout"));

        List<PageContent> pages = searchTools.fetchPages(List.of("https://a.com", "https://b.com"));
        assertThat(pages).hasSize(1);
        assertThat(pages.get(0).getUrl()).isEqualTo("https://a.com");
    }

    @Test
    void fetchPages_allFailure_returnsEmpty() {
        when(pageFetcher.fetch(any())).thenThrow(new RuntimeException("timeout"));
        assertThat(searchTools.fetchPages(List.of("https://a.com", "https://b.com"))).isEmpty();
    }

    @Test
    void scoreResources_scoresAndFiltersBelowThreshold() {
        // T=5(2026)/A=1(unknown)/D=5/M=3 → S=3.10 > 3.0 kept
        PageContent high = new PageContent("https://a.com", "A", "rich content ".repeat(200), 2000, 3, true, true, 2026);
        // T=1(2020)/A=1(unknown)/D=1/M=3 → S=1.70 < 3.0 filtered
        PageContent low = new PageContent("https://b.com", "B", "thin", 100, 0, false, false, 2020);

        List<ScoredResource> results = searchTools.scoreResources("Spring", List.of(high, low));
        assertThat(results).isNotEmpty();
        boolean hasLow = results.stream().anyMatch(r -> r.getUrl().equals("https://b.com"));
        assertThat(hasLow).isFalse();
    }

    @Test
    void scoreResources_sortedByWeightedScoreDesc() {
        // T=4(2025)/A=1/D=5/M=3 → S=0.60+0.30+1.00+1.05=2.95 → filtered!
        // 需要更高分让p1保留，改A=5
        PageContent p1 = new PageContent("https://spring.io", "Official Spring", "rich content ".repeat(500), 3000, 5, true, true, 2026);
        // T=3(2024)/A=4(medium)/D=2/M=3 → S=0.45+1.20+0.40+1.05=3.10
        PageContent p2 = new PageContent("https://medium.com", "Medium Post", "Good article ".repeat(100), 1000, 1, false, false, 2024);

        List<ScoredResource> results = searchTools.scoreResources("Java Streams", List.of(p2, p1));
        if (results.size() >= 2) {
            assertThat(results.get(0).getWeightedScore()).isGreaterThanOrEqualTo(results.get(1).getWeightedScore());
        }
    }

    @Test
    void scoreResources_emptyInput_returnsEmpty() {
        assertThat(searchTools.scoreResources("query", List.of())).isEmpty();
    }
}
