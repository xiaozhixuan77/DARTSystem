package com.dart.agent.scoring;

import java.util.Objects;

/**
 * 页面抓取结果 —— 传给评分器的输入。
 */
public class PageContent {

    private final String url;
    private final String title;
    private final String content;
    private final int wordCount;
    private final int codeBlockCount;
    private final boolean hasDiagram;
    private final boolean hasExercise;
    private final Integer year;

    public PageContent(String url, String title, String content,
                       int wordCount, int codeBlockCount,
                       boolean hasDiagram, boolean hasExercise,
                       Integer year) {
        this.url = url;
        this.title = title;
        this.content = content;
        this.wordCount = wordCount;
        this.codeBlockCount = codeBlockCount;
        this.hasDiagram = hasDiagram;
        this.hasExercise = hasExercise;
        this.year = year;
    }

    public String getUrl() { return url; }
    public String getTitle() { return title; }
    public String getContent() { return content; }
    public int getWordCount() { return wordCount; }
    public int getCodeBlockCount() { return codeBlockCount; }
    public boolean hasDiagram() { return hasDiagram; }
    public boolean hasExercise() { return hasExercise; }
    public Integer getYear() { return year; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PageContent that)) return false;
        return Objects.equals(url, that.url);
    }

    @Override
    public int hashCode() {
        return Objects.hash(url);
    }

    @Override
    public String toString() {
        return "PageContent{url='%s', title='%s', words=%d, code=%d, diagram=%s, exercise=%s, year=%d}"
                .formatted(url, title, wordCount, codeBlockCount, hasDiagram, hasExercise, year);
    }
}
