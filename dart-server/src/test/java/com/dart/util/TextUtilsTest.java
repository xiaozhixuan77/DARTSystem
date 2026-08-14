package com.dart.util;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TextUtilsTest {

    @Test
    void extractKeywords_mixedTitle_returnsEnAndChineseTokens() {
        List<String> keywords = TextUtils.extractKeywords("Spring Boot 自动配置原理是什么？");
        assertThat(keywords).containsExactly("spring", "boot", "自动配置原理");
    }

    @Test
    void extractKeywords_questionWordsExcluded() {
        List<String> keywords = TextUtils.extractKeywords("什么是 Java？怎么用？");
        assertThat(keywords).doesNotContain("什么", "怎么", "是", "用");
        assertThat(keywords).contains("java");
    }

    @Test
    void extractKeywords_punctuationRemovedFromChineseSegments() {
        assertThat(TextUtils.extractKeywords("自动配置原理？"))
                .containsExactly("自动配置原理");
        assertThat(TextUtils.extractKeywords("自动配置原理！"))
                .containsExactly("自动配置原理");
    }

    @Test
    void extractKeywords_shortTokensFiltered() {
        assertThat(TextUtils.extractKeywords("a b 语")).isEmpty();
    }

    @Test
    void extractKeywords_blankInput_returnsEmpty() {
        assertThat(TextUtils.extractKeywords(null)).isEmpty();
        assertThat(TextUtils.extractKeywords("   ")).isEmpty();
    }

    @Test
    void containsIgnoreCase_bidirectional() {
        assertThat(TextUtils.containsIgnoreCase("Spring Boot", "Spring")).isTrue();
        assertThat(TextUtils.containsIgnoreCase("Spring", "Spring Boot")).isTrue();
        assertThat(TextUtils.containsIgnoreCase("Spring Boot", "Spring Cloud")).isFalse();
        assertThat(TextUtils.containsIgnoreCase("A", "Apple")).isFalse(); // 单字符子串防误报
    }
}
