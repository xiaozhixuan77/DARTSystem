package com.dart.agent.splitter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
    void split_whenInputExceedsMaxSize_throwsException() {
        String huge = "x".repeat(5 * 1024 * 1024 + 1);
        assertThatThrownBy(() -> splitter.split(huge))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("5MB");
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

    // ===== 代码块感知 =====

    @Test
    void split_codeBlockWithBlankLines_keptAtomic() {
        // 前文本 200 + 代码块 ~333（含内部空行，<500）：总长超限，但代码块必须作为一个整体
        // 落在同一块中（旧实现会在内部空行处把代码块切开）
        StringBuilder code = new StringBuilder("```java\n");
        for (int i = 0; i < 4; i++) {
            code.append("    String a").append(i).append(" = \"value_a\"; // MARK_HALF_A\n");
        }
        code.append("\n");
        for (int i = 0; i < 4; i++) {
            code.append("    String b").append(i).append(" = \"value_b\"; // MARK_HALF_B\n");
        }
        code.append("```");
        String text = "说明文字。".repeat(40) + "\n\n" + code;

        List<ChunkResult> chunks = splitter.split(text);
        assertThat(chunks).anySatisfy(c -> assertThat(c.chunkText())
                .contains("MARK_HALF_A")
                .contains("MARK_HALF_B"));
        // 代码块内空行保留，未被 \n\n 切开
        assertThat(chunks).anySatisfy(c -> assertThat(c.chunkText())
                .contains("// MARK_HALF_A\n\n    String b0"));
    }

    @Test
    void split_oversizedCodeBlock_splitsByLineBoundary() {
        StringBuilder code = new StringBuilder("```java\n");
        for (int i = 0; i < 25; i++) {
            code.append("    int value").append(i).append(" = compute(value").append(i).append("); // 行").append(i).append("\n");
        }
        code.append("    import java. util. List;\n");
        code.append("```");

        List<ChunkResult> chunks = splitter.split(code.toString());
        assertThat(chunks.size()).isGreaterThanOrEqualTo(3);
        assertThat(chunks.get(0).chunkText()).contains("```java");
        assertThat(chunks.get(chunks.size() - 1).chunkText()).endsWith("```");
        // 含 ". " 的整行不被句点切碎
        assertThat(chunks).anySatisfy(c -> assertThat(c.chunkText()).contains("    import java. util. List;"));
        // 缩进行完整保留
        assertThat(chunks).anySatisfy(c -> assertThat(c.chunkText()).contains("    int value0 = compute(value0); // 行0"));
        // 每个块的所有行都是原文完整行（无截半行）
        Set<String> originalLines = code.toString().lines().map(String::strip).collect(Collectors.toSet());
        for (ChunkResult c : chunks) {
            for (String line : c.chunkText().lines().toList()) {
                assertThat(line.strip()).isIn(originalLines);
            }
        }
    }

    @Test
    void split_unclosedFence_treatedAsPlainText() {
        String text = "文本一。\n\n"
                + "~~~\n"
                + "~~~ 找不到伙伴的波浪围栏\n\n"
                + "文本二。\n\n"
                + "```java\n"
                + "int x = 1;\n"
                + "```\n"
                + "文本三。";
        List<ChunkResult> chunks = splitter.split(text);
        // 未闭合区域按普通文本：段落仍正常保留
        assertThat(chunks).anySatisfy(c -> assertThat(c.chunkText()).contains("文本一。"));
        assertThat(chunks).anySatisfy(c -> assertThat(c.chunkText()).contains("文本二。"));
        // 后续合法围栏仍被识别为代码块（完整成段）
        assertThat(chunks).anySatisfy(c -> assertThat(c.chunkText())
                .contains("```java")
                .contains("int x = 1;")
                .contains("```"));
        assertThat(chunks).anySatisfy(c -> assertThat(c.chunkText()).contains("文本三。"));
    }

    @Test
    void split_hashCommentInsideCodeBlock_notUsedAsTitle() {
        String text = "```bash\n"
                + "# 安装依赖\n"
                + "pip install requests\n"
                + "```\n\n"
                + "# 真实标题\n\n"
                + "正文内容。";
        List<ChunkResult> chunks = splitter.split(text);
        assertThat(chunks.get(0).sectionTitle()).isEqualTo("真实标题");
    }

    @Test
    void split_titleAfterCodeBlock_stillFound() {
        String text = "第一段没有标题的文本。\n\n"
                + "```\ncode\n```\n\n"
                + "# 后段标题\n\n"
                + "正文。";
        List<ChunkResult> chunks = splitter.split(text);
        assertThat(chunks.get(0).sectionTitle()).isEqualTo("后段标题");
    }

    @Test
    void split_longerFenceContainsShorterFence_notClosedEarly() {
        String text = "````\n"
                + "```\n"
                + "code inside\n"
                + "````\n\n"
                + "外文本。";
        List<ChunkResult> chunks = splitter.split(text);
        // 3 反引号行不提前闭合 4 反引号围栏：代码内容整体保留
        assertThat(chunks).anySatisfy(c -> assertThat(c.chunkText())
                .contains("````")
                .contains("```")
                .contains("code inside"));
    }

    @Test
    void split_emptyCodeBlock_noNoiseChunk() {
        String text = "第一段的内容写在这里。\n\n"
                + "```\n```\n\n"
                + "```java\n\n```\n\n"
                + "第二段的内容也写在这里。";
        List<ChunkResult> chunks = splitter.split(text);
        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).chunkText()).isEqualTo("第一段的内容写在这里。\n\n第二段的内容也写在这里。");
    }

    @Test
    void split_crlfClosedFence_recognized() {
        String text = "第一段。\r\n\r\n"
                + "```java\r\n"
                + "int x = 1;\r\n"
                + "```\r\n"
                + "\r\n"
                + "第二段。";
        List<ChunkResult> chunks = splitter.split(text);
        assertThat(chunks).anySatisfy(c -> assertThat(c.chunkText())
                .contains("```java")
                .contains("int x = 1;")
                .contains("```"));
    }

    @Test
    void split_overlapOnlyRemnant_notEmittedAsChunk() {
        // 甲乙两段：甲 flush 后产生 50 字符纯 overlap 残留，乙超长触发二次 flush 时
        // 残留不应作为独立块落盘（旧实现会产出 50 字重复块）
        String text = "甲".repeat(480) + "\n\n" + "乙".repeat(520);
        List<ChunkResult> chunks = splitter.split(text);
        assertThat(chunks).hasSize(2);
        for (ChunkResult c : chunks) {
            assertThat(c.chunkText().length()).isGreaterThan(100);
        }
    }

    @Test
    void split_oversizedInputWithFence_throwsBeforeParsing() {
        String huge = "```\n" + "x".repeat(5 * 1024 * 1024) + "\n```";
        assertThatThrownBy(() -> splitter.split(huge))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("5MB");
    }
}
