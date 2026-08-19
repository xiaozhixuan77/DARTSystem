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
            // 可变 List：SearchAgent 会对未抓取 URL 追加摘要兜底（生产实现返回 ArrayList）
            return new ArrayList<>(urls.stream().map(u -> new PageContent(u, "T", "c ".repeat(100), 1000, 1, false, false, 2026)).toList());
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
        // 主词含中文"配置"会启用污染快检 → title/snippet 必须含查询关键词防误判
        when(searchTools.searchWeb(anyString(), anyInt())).thenAnswer(inv -> {
            String q = inv.getArgument(0);
            if (q.equals("Spring Boot 配置")) {
                return List.of(new SearchResult("https://a.com", "Spring Boot 配置 A", "spring boot config guide"),
                        new SearchResult("https://b.com", "Spring Boot 配置 B", "spring boot config"));
            }
            if (q.equals("Spring Boot datasource 教程")) {
                return List.of(new SearchResult("https://b.com", "Spring Boot datasource B", "spring boot datasource"),
                        new SearchResult("https://c.com", "Spring Boot datasource C", "spring boot datasource guide"));
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
    void execute_variantsSupplement_capsFetchedUrlsToAvoidExpansion() {
        // 主词 4 条（未达标 8）+ 变体词各 8 条 = 20 个合并结果，抓取必须截断在上限 12
        when(searchTools.searchWeb(anyString(), anyInt())).thenAnswer(inv -> {
            String q = inv.getArgument(0);
            if (q.equals("q1")) {
                return IntStream.range(0, 4)
                        .mapToObj(i -> new SearchResult("https://p" + i + ".com", "T", "s"))
                        .toList();
            }
            int base = q.equals("q2") ? 100 : 200;
            return IntStream.range(0, 8)
                    .mapToObj(i -> new SearchResult("https://u" + (base + i) + ".com", "T", "s"))
                    .toList();
        });
        when(searchTools.fetchPages(anyList())).thenAnswer(inv -> {
            @SuppressWarnings("unchecked")
            List<String> urls = inv.getArgument(0);
            // 可变 List：SearchAgent 会对未抓取 URL 追加摘要兜底（生产实现返回 ArrayList）
            return new ArrayList<>(urls.stream().map(u -> new PageContent(u, "T", "c ".repeat(100), 1000, 1, false, false, 2026)).toList());
        });
        when(searchTools.scoreResources(anyString(), anyList())).thenAnswer(inv -> {
            @SuppressWarnings("unchecked")
            List<PageContent> pages = inv.getArgument(1);
            return pages.stream().map(p -> new ScoredResource(p.getUrl(), p.getTitle(), p.getContent(), 5, 4, 4, 4, 4.0)).toList();
        });

        searchAgent.execute(List.of("q1", "q2", "q3"), 8, emitter);

        // 20 个唯一 URL 只能抓取前 12 个；未抓取的后 8 个靠搜索摘要兜底参与评分
        verify(searchTools).fetchPages(argThat(urls -> urls.size() == 12));
    }

    @Test
    void execute_primarySatisfiesMaxResults_dropsVariantResults() {
        // 主词 5 条已达标（maxResults=5）→ 变体词结果不混入；但 searchWeb 仍并行调用
        when(searchTools.searchWeb(anyString(), anyInt())).thenAnswer(inv -> {
            String q = inv.getArgument(0);
            if (q.equals("primary")) {
                return IntStream.range(0, 5)
                        .mapToObj(i -> new SearchResult("https://site" + i + ".com", "T", "s"))
                        .toList();
            }
            return IntStream.range(0, 5)
                    .mapToObj(i -> new SearchResult("https://variant" + i + ".com", "T", "s"))
                    .toList();
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

        List<ScoredResource> result = searchAgent.execute(List.of("primary", "variant"), 5, emitter);

        verify(searchTools, times(2)).searchWeb(anyString(), anyInt());
        verify(searchTools).fetchPages(argThat(urls -> urls.size() == 5
                && urls.stream().noneMatch(u -> u.startsWith("https://variant"))));
        assertThat(result).allMatch(r -> r.getUrl().startsWith("https://site"));
    }

    @Test
    void execute_primaryInsufficient_supplementsWithVariants() {
        // 主词 3 条（未达标 5）→ 变体词结果补入（去重后）
        when(searchTools.searchWeb(anyString(), anyInt())).thenAnswer(inv -> {
            String q = inv.getArgument(0);
            if (q.equals("primary")) {
                return List.of(new SearchResult("https://a.com", "A", "s"),
                        new SearchResult("https://b.com", "B", "s"),
                        new SearchResult("https://c.com", "C", "s"));
            }
            return List.of(new SearchResult("https://b.com", "B", "s"),
                    new SearchResult("https://d.com", "D", "s"));
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

        searchAgent.execute(List.of("primary", "variant"), 5, emitter);

        // 3 条主词 + 1 条补充（b.com 重复去重）
        verify(searchTools).fetchPages(argThat(urls -> urls.equals(List.of("https://a.com", "https://b.com", "https://c.com", "https://d.com"))));
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

    // ─── S2: 中文主词污染快检与主词兜底 ─────────────────────────

    @Test
    void primaryResultsPolluted_discardedAndVariantsUsed() {
        // 中文主词返回全部无关结果（模拟 Bing 污染）→ 丢弃，改用变体词结果
        when(searchTools.searchWeb(anyString(), anyInt())).thenAnswer(inv -> {
            String q = inv.getArgument(0);
            if (q.equals("微服务 学习路线")) {
                return List.of(new SearchResult("https://news.com/1", "习近平同志重要讲话", "会议报道"),
                        new SearchResult("https://messenger.com", "Messenger", "Instant messaging app"));
            }
            // 变体词（英文）返回正常结果
            return List.of(new SearchResult("https://microservices.io", "What are microservices?", "microservices guide"));
        });
        when(searchTools.fetchPages(anyList())).thenAnswer(inv -> {
            @SuppressWarnings("unchecked")
            List<String> urls = inv.getArgument(0);
            return urls.stream().map(u -> new PageContent(u, "T", "microservices content ".repeat(50), 800, 1, false, false, 2026)).toList();
        });
        when(searchTools.scoreResources(anyString(), anyList())).thenAnswer(inv -> {
            @SuppressWarnings("unchecked")
            List<PageContent> pages = inv.getArgument(1);
            return pages.stream().map(p -> new ScoredResource(p.getUrl(), p.getTitle(), p.getContent(), 5, 4, 4, 4, 4.0)).toList();
        });

        searchAgent.execute(List.of("微服务 学习路线", "microservices learning path"), 5, emitter);

        // 污染主词结果被丢弃，只抓变体词（英文）的结果
        verify(searchTools).fetchPages(argThat(urls -> urls.equals(List.of("https://microservices.io"))));
    }

    @Test
    void primaryResultsRelevant_kept() {
        // 中文主词返回含关键词的正常结果 → 不误判，原样保留
        when(searchTools.searchWeb(anyString(), anyInt())).thenAnswer(inv -> {
            String q = inv.getArgument(0);
            if (q.equals("微服务 学习路线")) {
                return List.of(new SearchResult("https://cnblogs.com/ms", "微服务学习路线总结", "微服务架构学习指南"));
            }
            return List.of();
        });
        when(searchTools.fetchPages(anyList())).thenAnswer(inv -> {
            @SuppressWarnings("unchecked")
            List<String> urls = inv.getArgument(0);
            return urls.stream().map(u -> new PageContent(u, "T", "microservices ".repeat(100), 1000, 1, false, false, 2026)).toList();
        });
        when(searchTools.scoreResources(anyString(), anyList())).thenAnswer(inv -> {
            @SuppressWarnings("unchecked")
            List<PageContent> pages = inv.getArgument(1);
            return pages.stream().map(p -> new ScoredResource(p.getUrl(), p.getTitle(), p.getContent(), 5, 4, 4, 4, 4.0)).toList();
        });

        searchAgent.execute(List.of("微服务 学习路线", "microservices learning path"), 5, emitter);

        verify(searchTools).fetchPages(argThat(urls -> urls.equals(List.of("https://cnblogs.com/ms"))));
    }

    @Test
    void primaryResultsEmpty_variantsUsed() {
        // Bing 对主词返回空 → 变体词结果兜底（修复原"主词空直接返回空"）
        when(searchTools.searchWeb(anyString(), anyInt())).thenAnswer(inv -> {
            String q = inv.getArgument(0);
            if (q.equals("微服务 学习路线")) {
                return List.of();
            }
            return List.of(new SearchResult("https://microservices.io", "What are microservices?", "microservices guide"));
        });
        when(searchTools.fetchPages(anyList())).thenAnswer(inv -> {
            @SuppressWarnings("unchecked")
            List<String> urls = inv.getArgument(0);
            return urls.stream().map(u -> new PageContent(u, "T", "microservices ".repeat(100), 1000, 1, false, false, 2026)).toList();
        });
        when(searchTools.scoreResources(anyString(), anyList())).thenAnswer(inv -> {
            @SuppressWarnings("unchecked")
            List<PageContent> pages = inv.getArgument(1);
            return pages.stream().map(p -> new ScoredResource(p.getUrl(), p.getTitle(), p.getContent(), 5, 4, 4, 4, 4.0)).toList();
        });

        List<ScoredResource> result = searchAgent.execute(List.of("微服务 学习路线", "microservices learning path"), 5, emitter);

        assertThat(result).isNotEmpty();
        verify(searchTools).fetchPages(argThat(urls -> urls.equals(List.of("https://microservices.io"))));
    }

    @Test
    void isPolluted_englishQuery_skipped() {
        // 英文主词不启用污染检查——无关结果也原样保留（英文查询 Bing 稳定）
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
            return pages.stream().map(p -> new ScoredResource(p.getUrl(), p.getTitle(), p.getContent(), 5, 4, 4, 4, 4.0)).toList();
        });

        searchAgent.execute(List.of("Spring Boot"), 5, emitter);

        verify(searchTools).fetchPages(argThat(urls -> urls.equals(List.of("https://site.com"))));
    }

    @Test
    void isPolluted_chineseLongQuery_notMisjudged() {
        // 中文长查询（无停用词分隔）正常结果不误判——词集按空白拆分，不粘连
        String query = "微服务 学习路线 Spring Cloud Docker Kubernetes 必备知识";
        when(searchTools.searchWeb(anyString(), anyInt())).thenAnswer(inv -> {
            String q = inv.getArgument(0);
            if (q.equals(query)) {
                return List.of(new SearchResult("https://cnblogs.com/1", "微服务架构学习路线", "Spring Cloud 微服务教程"),
                        new SearchResult("https://redhat.com/1", "什么是微服务", "microservices 架构指南"));
            }
            return List.of();
        });
        when(searchTools.fetchPages(anyList())).thenAnswer(inv -> {
            @SuppressWarnings("unchecked")
            List<String> urls = inv.getArgument(0);
            return urls.stream().map(u -> new PageContent(u, "T", "microservices ".repeat(100), 1000, 1, false, false, 2026)).toList();
        });
        when(searchTools.scoreResources(anyString(), anyList())).thenAnswer(inv -> {
            @SuppressWarnings("unchecked")
            List<PageContent> pages = inv.getArgument(1);
            return pages.stream().map(p -> new ScoredResource(p.getUrl(), p.getTitle(), p.getContent(), 5, 4, 4, 4, 4.0)).toList();
        });

        searchAgent.execute(List.of(query, "microservices learning path"), 5, emitter);

        // 主词结果保留（未被误判污染）：两个 URL 都抓
        verify(searchTools).fetchPages(argThat(urls -> urls.equals(List.of(
                "https://cnblogs.com/1", "https://redhat.com/1"))));
    }

    @Test
    void isPolluted_englishOnlyResults_notMisjudged() {
        // Bing 返回全英文站结果（title 无中文词）→ 靠英文映射词 microservices 命中，不误判
        when(searchTools.searchWeb(anyString(), anyInt())).thenAnswer(inv -> {
            String q = inv.getArgument(0);
            if (q.equals("微服务 学习路线")) {
                return List.of(new SearchResult("https://microservices.io", "What are microservices?",
                        "Microservices - a software architecture style"));
            }
            return List.of();
        });
        when(searchTools.fetchPages(anyList())).thenAnswer(inv -> {
            @SuppressWarnings("unchecked")
            List<String> urls = inv.getArgument(0);
            return urls.stream().map(u -> new PageContent(u, "T", "microservices ".repeat(100), 1000, 1, false, false, 2026)).toList();
        });
        when(searchTools.scoreResources(anyString(), anyList())).thenAnswer(inv -> {
            @SuppressWarnings("unchecked")
            List<PageContent> pages = inv.getArgument(1);
            return pages.stream().map(p -> new ScoredResource(p.getUrl(), p.getTitle(), p.getContent(), 5, 4, 4, 4, 4.0)).toList();
        });

        searchAgent.execute(List.of("微服务 学习路线", "microservices learning path"), 5, emitter);

        verify(searchTools).fetchPages(argThat(urls -> urls.equals(List.of("https://microservices.io"))));
    }
}
