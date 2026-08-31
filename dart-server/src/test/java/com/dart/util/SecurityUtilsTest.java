package com.dart.util;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityUtilsTest {

    @AfterEach
    void cleanup() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void getCurrentUserId_readsXUserIdHeader() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-User-Id", "7");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        assertThat(SecurityUtils.getCurrentUserId()).isEqualTo(7L);
    }

    @Test
    void getCurrentUserId_invalidHeader_fallsBackToDefault() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-User-Id", "abc");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        assertThat(SecurityUtils.getCurrentUserId()).isEqualTo(3L);
    }

    @Test
    void getCurrentUserId_noRequestContext_fallsBackToDefault() {
        assertThat(SecurityUtils.getCurrentUserId()).isEqualTo(3L);
    }
}
