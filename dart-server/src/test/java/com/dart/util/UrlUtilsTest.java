package com.dart.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UrlUtilsTest {

    @Test
    void trimTrailingSlash_stripsSingleTrailingSlash() {
        assertThat(UrlUtils.trimTrailingSlash("https://api.deepseek.com/"))
                .isEqualTo("https://api.deepseek.com");
    }

    @Test
    void trimTrailingSlash_stripsMultipleTrailingSlashes() {
        assertThat(UrlUtils.trimTrailingSlash("https://api.deepseek.com///"))
                .isEqualTo("https://api.deepseek.com");
    }

    @Test
    void trimTrailingSlash_null_returnsNull() {
        assertThat(UrlUtils.trimTrailingSlash(null)).isNull();
    }

    @Test
    void trimTrailingSlash_noSlash_unchanged() {
        assertThat(UrlUtils.trimTrailingSlash("https://api.deepseek.com"))
                .isEqualTo("https://api.deepseek.com");
    }

    @Test
    void trimTrailingSlash_trimsWhitespace() {
        assertThat(UrlUtils.trimTrailingSlash("  https://api.deepseek.com/  "))
                .isEqualTo("https://api.deepseek.com");
    }
}
