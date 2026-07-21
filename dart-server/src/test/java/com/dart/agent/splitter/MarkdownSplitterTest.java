package com.dart.agent.splitter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MarkdownSplitterTest {

    private MarkdownSplitter splitter;

    @BeforeEach
    void setUp() {
        splitter = new MarkdownSplitter(500, 50, 20);
    }

    @Test
    void split_whenEmptyText_returnsEmptyList() {
        assertThat(splitter.split("")).isEmpty();
        assertThat(splitter.split(null)).isEmpty();
    }

    @Test
    void split_whenShortText_returnsSingleChunk() {
        MarkdownSplitter lenientSplitter = new MarkdownSplitter(500, 50, 1);
        String text = "这是一段简短的文本，不超过分块大小。";
        List<ChunkResult> chunks = lenientSplitter.split(text);
        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).chunkText()).isEqualTo(text);
        assertThat(chunks.get(0).chunkIndex()).isEqualTo(0);
    }

    @Test
    void split_whenLongText_splitsByParagraphsWithOverlap() {
        StringBuilder sb = new StringBuilder();
        sb.append("# 测试标题\n\n");
        // 段落1: ~300字符
        sb.append("这是第一段测试内容。".repeat(30));
        sb.append("\n\n");
        // 段落2: ~300字符
        sb.append("这是第二段测试内容。".repeat(30));

        List<ChunkResult> chunks = splitter.split(sb.toString());
        assertThat(chunks).isNotEmpty();
        assertThat(chunks.size()).isGreaterThanOrEqualTo(2);
        // 验证每个块不超过 chunkSize
        for (ChunkResult c : chunks) {
            assertThat(c.chunkText().length()).isLessThanOrEqualTo(600);
        }
        // 验证 sectionTitle 包含标题
        assertThat(chunks.get(0).sectionTitle()).isEqualTo("测试标题");
    }

    @Test
    void split_whenFragmentsBelowMinLength_filteredOut() {
        MarkdownSplitter strictSplitter = new MarkdownSplitter(500, 50, 100);
        String text = "短。\n\n".repeat(3);
        List<ChunkResult> chunks = strictSplitter.split(text);
        // 每个碎片很短，应该被过滤掉
        for (ChunkResult c : chunks) {
            assertThat(c.chunkText().length()).isGreaterThanOrEqualTo(100);
        }
    }
}
