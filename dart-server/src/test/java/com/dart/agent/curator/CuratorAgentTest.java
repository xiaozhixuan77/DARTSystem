package com.dart.agent.curator;

import com.dart.agent.scoring.ScoredResource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import com.dart.broadcast.SseSink;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CuratorAgentTest {

    @Mock
    private CuratorTools curatorTools;

    private SseSink sink;
    private CuratorAgent curatorAgent;

    private static ScoredResource makeResource(String url, String title, double score) {
        return new ScoredResource(url, title, "content ".repeat(50), 5, 4, 3, 4, score);
    }

    @BeforeEach
    void setUp() {
        sink = mock(SseSink.class);
        curatorAgent = new CuratorAgent(curatorTools);
    }

    @Test
    void curate_normalFlow_returnsReport() throws IOException {
        ScoredResource r1 = makeResource("https://a.com", "Title A", 4.5);
        CuratedReport expectedReport = new CuratedReport("query", "# Report\n\nContent...",
                List.of("https://a.com"), Instant.now());
        when(curatorTools.deduplicate(anyList())).thenReturn(List.of(r1));
        when(curatorTools.generateReport(anyString(), anyList(), any(), any(), isNull(), eq(false), isNull())).thenReturn(expectedReport);
        when(curatorTools.validateReport(any())).thenReturn(true);

        CuratedReport report = curatorAgent.curate("query", List.of(r1), sink, null, null, false, null);
        assertThat(report).isNotNull();
        verify(curatorTools).deduplicate(anyList());
        verify(curatorTools).generateReport(anyString(), anyList(), any(), any(), isNull(), eq(false), isNull());
        verify(sink, atLeastOnce()).sendEvent(any(), any());
    }

    @Test
    void curate_withIntent_passesStyleHintToGenerateReport() throws IOException {
        ScoredResource r1 = makeResource("https://a.com", "Title A", 4.5);
        CuratedReport expectedReport = new CuratedReport("query", "# Report\n\nContent...",
                List.of("https://a.com"), Instant.now());
        when(curatorTools.deduplicate(anyList())).thenReturn(List.of(r1));
        when(curatorTools.generateReport(anyString(), anyList(), any(), eq("deep_dive"), isNull(), eq(false), isNull())).thenReturn(expectedReport);
        when(curatorTools.validateReport(any())).thenReturn(true);

        CuratedReport report = curatorAgent.curate("query", List.of(r1), sink, "deep_dive", null, false, null);

        assertThat(report).isNotNull();
        verify(curatorTools).generateReport(anyString(), anyList(), any(), eq("deep_dive"), isNull(), eq(false), isNull());
    }

    @Test
    void curate_withoutIntent_threeArgDelegatesWithNullStyle() throws IOException {
        ScoredResource r1 = makeResource("https://a.com", "Title A", 4.5);
        CuratedReport expectedReport = new CuratedReport("query", "# Report\n\nContent...",
                List.of("https://a.com"), Instant.now());
        when(curatorTools.deduplicate(anyList())).thenReturn(List.of(r1));
        when(curatorTools.generateReport(anyString(), anyList(), any(), isNull(), isNull(), eq(false), isNull())).thenReturn(expectedReport);
        when(curatorTools.validateReport(any())).thenReturn(true);

        // 3 参入口 → 4 参委托（styleHint=null, sessionSummary=null）
        curatorAgent.curate("query", List.of(r1), sink, null, null, false, null);

        verify(curatorTools).generateReport(anyString(), anyList(), any(), isNull(), isNull(), eq(false), isNull());
    }

    @Test
    void curate_withSessionSummary_passesToGenerateReport() throws IOException {
        ScoredResource r1 = makeResource("https://a.com", "Title A", 4.5);
        CuratedReport expectedReport = new CuratedReport("query", "# Report\n\nContent...",
                List.of("https://a.com"), Instant.now());
        when(curatorTools.deduplicate(anyList())).thenReturn(List.of(r1));
        when(curatorTools.generateReport(anyString(), anyList(), any(), any(), eq("主题：Spring IoC"), eq(false), isNull()))
                .thenReturn(expectedReport);
        when(curatorTools.validateReport(any())).thenReturn(true);

        CuratedReport report = curatorAgent.curate("query", List.of(r1), sink, "deep_dive", "主题：Spring IoC", false, null);

        assertThat(report).isNotNull();
        verify(curatorTools).generateReport(anyString(), anyList(), any(), eq("deep_dive"), eq("主题：Spring IoC"), eq(false), isNull());
    }

    @Test
    void curate_validationFails_fallsBackToFallback() throws IOException {
        ScoredResource r1 = makeResource("https://a.com", "Title A", 4.5);
        CuratedReport invalidReport = new CuratedReport("query", "too short", List.of(), Instant.now());
        CuratedReport fallback = new CuratedReport("query", "# Fallback\n\nContent ".repeat(10),
                List.of("https://a.com"), Instant.now());
        when(curatorTools.deduplicate(anyList())).thenReturn(List.of(r1));
        when(curatorTools.generateReport(anyString(), anyList(), any(), any(), isNull(), eq(false), isNull())).thenReturn(invalidReport);
        when(curatorTools.validateReport(invalidReport)).thenReturn(false);
        when(curatorTools.fallbackReport(anyString(), anyList())).thenReturn(fallback);

        CuratedReport report = curatorAgent.curate("query", List.of(r1), sink, null, null, false, null);
        assertThat(report.markdown()).contains("Fallback");
    }

    @Test
    void curate_validationFails_regenerateSucceeds_returnsRegenerated() throws IOException {
        ScoredResource r1 = makeResource("https://a.com", "Title A", 4.5);
        CuratedReport invalidReport = new CuratedReport("query", "too short", List.of(), Instant.now());
        String rewritten = "# 重写报告\n\n核心内容 ".repeat(20);
        when(curatorTools.deduplicate(anyList())).thenReturn(List.of(r1));
        when(curatorTools.generateReport(anyString(), anyList(), any(), any(), isNull(), eq(false), isNull())).thenReturn(invalidReport);
        when(curatorTools.validateReport(invalidReport)).thenReturn(false);
        when(curatorTools.regenerateReport(anyString(), anyList())).thenReturn(rewritten);
        // 语义匹配而非实例 equals：CuratorAgent 内部重写报告的 generatedAt 由 report 携带，
        // 用 record 实例匹配依赖两个 Instant.now() 相等（Windows 时钟粒度侥幸通过，其他平台随机失败）
        when(curatorTools.validateReport(argThat(r -> r.markdown().contains("重写报告")))).thenReturn(true);

        CuratedReport report = curatorAgent.curate("query", List.of(r1), sink, null, null, false, null);

        // 重写报告替换无效报告，不再降级纯列表
        assertThat(report.markdown()).contains("重写报告");
        verify(curatorTools).regenerateReport("query", List.of(r1));
        verify(curatorTools, never()).fallbackReport(anyString(), anyList());
    }

    @Test
    void curate_validationFails_regenerateReturnsNull_fallsBackToFallback() throws IOException {
        ScoredResource r1 = makeResource("https://a.com", "Title A", 4.5);
        CuratedReport invalidReport = new CuratedReport("query", "too short", List.of(), Instant.now());
        CuratedReport fallback = new CuratedReport("query", "# Fallback\n\nContent ".repeat(10),
                List.of("https://a.com"), Instant.now());
        when(curatorTools.deduplicate(anyList())).thenReturn(List.of(r1));
        when(curatorTools.generateReport(anyString(), anyList(), any(), any(), isNull(), eq(false), isNull())).thenReturn(invalidReport);
        when(curatorTools.validateReport(invalidReport)).thenReturn(false);
        when(curatorTools.regenerateReport(anyString(), anyList())).thenReturn(null);
        when(curatorTools.fallbackReport(anyString(), anyList())).thenReturn(fallback);

        CuratedReport report = curatorAgent.curate("query", List.of(r1), sink, null, null, false, null);
        assertThat(report.markdown()).contains("Fallback");
    }

    @Test
    void curate_validationFails_regeneratedStillInvalid_fallsBackToFallback() throws IOException {
        ScoredResource r1 = makeResource("https://a.com", "Title A", 4.5);
        CuratedReport invalidReport = new CuratedReport("query", "too short", List.of(), Instant.now());
        CuratedReport fallback = new CuratedReport("query", "# Fallback\n\nContent ".repeat(10),
                List.of("https://a.com"), Instant.now());
        when(curatorTools.deduplicate(anyList())).thenReturn(List.of(r1));
        when(curatorTools.generateReport(anyString(), anyList(), any(), any(), isNull(), eq(false), isNull())).thenReturn(invalidReport);
        when(curatorTools.validateReport(any())).thenReturn(false);
        when(curatorTools.regenerateReport(anyString(), anyList()))
                .thenReturn("# 仍然过短");
        when(curatorTools.fallbackReport(anyString(), anyList())).thenReturn(fallback);

        CuratedReport report = curatorAgent.curate("query", List.of(r1), sink, null, null, false, null);
        // 重写内容仍校验失败 → 最终降级 fallback
        assertThat(report.markdown()).contains("Fallback");
    }

    @Test
    void curate_dedupReturnsEmpty_returnsEmptyReport() throws IOException {
        when(curatorTools.deduplicate(anyList())).thenReturn(List.of());
        ScoredResource r1 = makeResource("https://a.com", "A", 3.5);
        CuratedReport emptyReport = new CuratedReport("query", "无结果", List.of(), Instant.now());
        when(curatorTools.fallbackReport(anyString(), anyList())).thenReturn(emptyReport);

        CuratedReport report = curatorAgent.curate("query", List.of(r1), sink, null, null, false, null);
        assertThat(report).isNotNull();
        verify(curatorTools, never()).generateReport(anyString(), anyList(), any(), any(), isNull(), eq(false), isNull());
    }

    @Test
    void curate_sendsStatusEvents() throws IOException {
        ScoredResource r1 = makeResource("https://a.com", "Title A", 4.5);
        CuratedReport expected = new CuratedReport("query", "# Ok ".repeat(20),
                List.of("https://a.com"), Instant.now());
        when(curatorTools.deduplicate(anyList())).thenReturn(List.of(r1));
        when(curatorTools.generateReport(anyString(), anyList(), any(), any(), isNull(), eq(false), isNull())).thenReturn(expected);
        when(curatorTools.validateReport(any())).thenReturn(true);

        curatorAgent.curate("query", List.of(r1), sink, null, null, false, null);
        verify(sink, atLeastOnce()).sendEvent(any(), any());
    }

    @Test
    void curateDegraded_passesDegradedToGenerateReport() throws IOException {
        ScoredResource r1 = makeResource("https://a.com", "Title A", 4.5);
        CuratedReport expectedReport = new CuratedReport("query", "# Report\n\nContent...",
                List.of("https://a.com"), Instant.now());
        when(curatorTools.deduplicate(anyList())).thenReturn(List.of(r1));
        when(curatorTools.generateReport(anyString(), anyList(), any(), any(), isNull(), eq(true), isNull()))
                .thenReturn(expectedReport);
        when(curatorTools.validateReport(any())).thenReturn(true);

        CuratedReport report = curatorAgent.curate("query", List.of(r1), sink, "how_to", null, true, null);

        assertThat(report).isNotNull();
        verify(curatorTools).generateReport(anyString(), anyList(), any(), eq("how_to"), isNull(), eq(true), isNull());
    }

    @Test
    void curate_withDraftConsumer_passesThroughToGenerateReport() throws IOException {
        ScoredResource r1 = makeResource("https://a.com", "Title A", 4.5);
        CuratedReport expectedReport = new CuratedReport("query", "# Report\n\nContent...",
                List.of("https://a.com"), Instant.now());
        when(curatorTools.deduplicate(anyList())).thenReturn(List.of(r1));
        when(curatorTools.generateReport(anyString(), anyList(), any(), any(), isNull(), eq(false), any()))
                .thenReturn(expectedReport);
        when(curatorTools.validateReport(any())).thenReturn(true);

        Consumer<String> consumer = snapshot -> { };
        CuratedReport report = curatorAgent.curate("query", List.of(r1), sink, null, null, false, consumer);

        assertThat(report).isNotNull();
        verify(curatorTools).generateReport(anyString(), anyList(), any(), any(), isNull(), eq(false), same(consumer));
    }
}
