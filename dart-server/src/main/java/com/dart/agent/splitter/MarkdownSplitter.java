package com.dart.agent.splitter;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Markdown 文本分块器。
 * 按段落分割，超限时按句子强制切分，保留标题上下文。
 */
public class MarkdownSplitter {

    private static final Pattern HEADING_PATTERN = Pattern.compile("^#{1,6}\\s+(.+)$", Pattern.MULTILINE);
    private static final Pattern SENTENCE_SPLIT = Pattern.compile("(?<=[。！？.!?])\\s*");

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

        String sectionTitle = extractTitle(markdown);
        String[] paragraphs = markdown.split("\n\n+");
        List<String> rawChunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        for (String para : paragraphs) {
            String trimmed = para.trim();
            if (trimmed.isEmpty()) {
                continue;
            }

            if (current.length() + trimmed.length() + 2 > chunkSize && current.length() > 0) {
                rawChunks.add(current.toString().trim());
                // overlap: 保留上一块末尾内容
                String prevText = current.toString();
                int overlapStart = Math.max(0, prevText.length() - overlap);
                current = new StringBuilder(prevText.substring(overlapStart));
            }

            if (trimmed.length() > chunkSize) {
                // 超长段落按句子切分
                String[] sentences = SENTENCE_SPLIT.split(trimmed);
                for (String sentence : sentences) {
                    String s = sentence.trim();
                    if (s.isEmpty()) {
                        continue;
                    }
                    if (current.length() + s.length() + 1 > chunkSize && current.length() > 0) {
                        rawChunks.add(current.toString().trim());
                        String prevText = current.toString();
                        int overlapStart = Math.max(0, prevText.length() - overlap);
                        current = new StringBuilder(prevText.substring(overlapStart));
                    }
                    if (!current.isEmpty()) {
                        current.append(" ");
                    }
                    current.append(s);
                }
            } else {
                if (!current.isEmpty()) {
                    current.append("\n\n");
                }
                current.append(trimmed);
            }
        }

        if (current.length() > 0) {
            rawChunks.add(current.toString().trim());
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

    private String extractTitle(String markdown) {
        Matcher m = HEADING_PATTERN.matcher(markdown);
        if (m.find()) {
            return m.group(1).trim();
        }
        return "";
    }
}
