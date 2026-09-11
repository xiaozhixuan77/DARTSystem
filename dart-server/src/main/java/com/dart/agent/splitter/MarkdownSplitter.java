package com.dart.agent.splitter;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Markdown 文本分块器。
 * 按段落分割，超限时按句子强制切分，保留标题上下文；
 * 围栏代码块内不切分：短块整体保留，超长块按代码行边界切分。
 */
public class MarkdownSplitter {

    private static final Pattern HEADING_PATTERN = Pattern.compile("^#{1,6}\\s+(.+)$", Pattern.MULTILINE);
    private static final Pattern SENTENCE_SPLIT = Pattern.compile("(?<=[。！？.!?])\\s*");
    /** 围栏行：行首 ≤3 空格/Tab 缩进的 ``` 或 ~~~（可带语言后缀） */
    private static final Pattern FENCE_LINE = Pattern.compile("(?m)^[ \\t]{0,3}(`{3,}|~{3,})[^\\n]*");
    private static final int MAX_INPUT_LENGTH = 5 * 1024 * 1024; // 5MB

    private final int chunkSize;
    private final int overlap;
    private final int minLength;

    public MarkdownSplitter(int chunkSize, int overlap, int minLength) {
        this.chunkSize = chunkSize;
        this.overlap = overlap;
        this.minLength = minLength;
    }

    public List<ChunkResult> split(String markdown) {
        if (markdown == null || markdown.isEmpty()) {
            return List.of();
        }
        if (markdown.length() > MAX_INPUT_LENGTH) {
            throw new IllegalArgumentException("输入文本过大，最大支持 5MB");
        }

        List<Segment> segments = parseSegments(markdown);
        String sectionTitle = extractTitle(segments);

        Accumulator acc = new Accumulator();
        List<String> rawChunks = new ArrayList<>();
        for (Segment segment : segments) {
            if (segment.code()) {
                appendCodeBlock(acc, segment.text(), rawChunks);
            } else {
                appendText(acc, segment.text(), rawChunks);
            }
        }
        if (acc.content.length() > 0 && !acc.overlapOnly) {
            rawChunks.add(acc.content.toString().trim());
        }

        List<ChunkResult> results = new ArrayList<>();
        int index = 0;
        for (String text : rawChunks) {
            if (text.length() >= minLength) {
                results.add(new ChunkResult(sectionTitle, text, index++));
            }
        }
        return results;
    }

    private void appendText(Accumulator acc, String text, List<String> out) {
        String[] paragraphs = text.split("\n\n+");
        for (String paragraph : paragraphs) {
            String trimmed = paragraph.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (acc.content.length() + trimmed.length() + 2 > chunkSize && acc.content.length() > 0) {
                prepareForNewContent(acc, out);
            }
            if (trimmed.length() > chunkSize) {
                String[] sentences = SENTENCE_SPLIT.split(trimmed);
                for (String sentence : sentences) {
                    String s = sentence.trim();
                    if (s.isEmpty()) {
                        continue;
                    }
                    if (acc.content.length() + s.length() + 1 > chunkSize && acc.content.length() > 0) {
                        prepareForNewContent(acc, out);
                    }
                    if (acc.content.length() > 0) {
                        acc.content.append(" ");
                    }
                    acc.content.append(s);
                    acc.overlapOnly = false;
                }
            } else {
                if (acc.content.length() > 0) {
                    acc.content.append("\n\n");
                }
                acc.content.append(trimmed);
                acc.overlapOnly = false;
            }
        }
    }

    private void appendCodeBlock(Accumulator acc, String code, List<String> out) {
        if (isBlankCodeBlock(code)) {
            return;
        }
        if (code.length() <= chunkSize) {
            if (acc.content.length() + code.length() + 2 > chunkSize && acc.content.length() > 0) {
                prepareForNewContent(acc, out);
            }
            if (acc.content.length() > 0) {
                acc.content.append("\n\n");
            }
            acc.content.append(code);
            acc.overlapOnly = false;
            return;
        }
        // 超长代码块按行切分：行是代码原子单位；子块之间不重叠（同块内行序天然连续），
        // 前置 flush 同样零 overlap：残留片段粘到围栏行会让围栏不在行首，markdown 渲染失效
        if (acc.content.length() > 0) {
            if (!acc.overlapOnly) {
                out.add(acc.content.toString().trim());
            }
            acc.content.setLength(0);
            acc.overlapOnly = false;
        }
        int from = 0;
        while (from < code.length()) {
            int nl = code.indexOf('\n', from);
            String line = (nl < 0) ? code.substring(from) : code.substring(from, nl + 1);
            if (acc.content.length() + line.length() > chunkSize && acc.content.length() > 0) {
                out.add(acc.content.toString().stripTrailing());
                acc.content.setLength(0);
                acc.overlapOnly = false;
            }
            acc.content.append(line);
            acc.overlapOnly = false;
            if (nl < 0) {
                break;
            }
            from = nl + 1;
        }
    }

    /** 新内容放不下时：纯 overlap 残留直接丢弃，否则落块并延续 overlap */
    private void prepareForNewContent(Accumulator acc, List<String> out) {
        if (acc.overlapOnly) {
            acc.content.setLength(0);
            acc.overlapOnly = false;
        } else {
            flushWithOverlap(acc, out);
        }
    }

    private void flushWithOverlap(Accumulator acc, List<String> out) {
        out.add(acc.content.toString().trim());
        String prev = acc.content.toString();
        int start = Math.max(0, prev.length() - overlap);
        acc.content.setLength(0);
        acc.content.append(prev.substring(start));
        acc.overlapOnly = true;
    }

    private List<Segment> parseSegments(String markdown) {
        List<int[]> codeRanges = new ArrayList<>();
        Matcher m = FENCE_LINE.matcher(markdown);
        int searchFrom = 0;
        while (searchFrom < markdown.length()) {
            m.region(searchFrom, markdown.length());
            if (!m.find()) {
                break;
            }
            String fence = m.group(1);
            char fenceChar = fence.charAt(0);
            int openRun = fence.length();
            int openStart = m.start();
            int openEnd = m.end();
            // 未闭合围栏按普通文本处理，从下一行继续扫描（后续合法围栏仍可识别）
            int closeEnd = (openEnd < markdown.length())
                    ? findClosing(markdown, openEnd, fenceChar, openRun) : -1;
            if (closeEnd > 0) {
                codeRanges.add(new int[]{openStart, closeEnd});
                searchFrom = closeEnd;
            } else {
                searchFrom = openEnd;
            }
        }

        List<Segment> segments = new ArrayList<>();
        int pos = 0;
        for (int[] range : codeRanges) {
            if (range[0] > pos) {
                segments.add(new Segment(false, markdown.substring(pos, range[0])));
            }
            segments.add(new Segment(true, markdown.substring(range[0], range[1])));
            pos = range[1];
        }
        if (pos < markdown.length()) {
            segments.add(new Segment(false, markdown.substring(pos)));
        }
        return segments;
    }

    /** 从开围栏行之后逐行找闭合围栏：同字符、run 长度不短于开围栏、行内无其他字符 */
    private int findClosing(String markdown, int openEnd, char fenceChar, int openRun) {
        int pos = openEnd + 1;
        while (pos < markdown.length()) {
            int nl = markdown.indexOf('\n', pos);
            int lineEnd = (nl < 0) ? markdown.length() : nl;
            String stripped = markdown.substring(pos, lineEnd).strip();
            if (!stripped.isEmpty() && stripped.length() >= openRun
                    && stripped.chars().allMatch(c -> c == fenceChar)) {
                return lineEnd;
            }
            if (nl < 0) {
                break;
            }
            pos = nl + 1;
        }
        return -1;
    }

    private boolean isBlankCodeBlock(String code) {
        for (String line : code.split("\n")) {
            String stripped = line.strip();
            if (stripped.isEmpty()) {
                continue;
            }
            int i = 0;
            while (i < stripped.length() && (stripped.charAt(i) == '`' || stripped.charAt(i) == '~')) {
                i++;
            }
            if (i >= 3) {
                continue;
            }
            return false;
        }
        return true;
    }

    /** 标题取首个非代码区的 heading（与现行为等价：代码块内 # 注释不夺标题） */
    private String extractTitle(List<Segment> segments) {
        for (Segment segment : segments) {
            if (segment.code()) {
                continue;
            }
            Matcher m = HEADING_PATTERN.matcher(segment.text());
            if (m.find()) {
                return m.group(1).trim();
            }
        }
        return "";
    }

    private record Segment(boolean code, String text) {}

    private static final class Accumulator {
        private final StringBuilder content = new StringBuilder();
        private boolean overlapOnly;
    }
}
