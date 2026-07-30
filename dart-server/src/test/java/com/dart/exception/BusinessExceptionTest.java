package com.dart.exception;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BusinessExceptionTest {

    @Test
    void constructor_withErrorCodeOnly() {
        BusinessException ex = new BusinessException(ErrorCode.USERNAME_EXISTS);

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.USERNAME_EXISTS);
        assertThat(ex.getMessage()).isEqualTo("用户名已存在");
        assertThat(ex.getSuggestion()).isNull();
    }

    @Test
    void constructor_withErrorCodeAndSuggestion() {
        BusinessException ex = new BusinessException(ErrorCode.SEARCH_TIMEOUT, "请检查网络连接后重试");

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.SEARCH_TIMEOUT);
        assertThat(ex.getMessage()).isEqualTo("搜索引擎响应超时，请稍后重试");
        assertThat(ex.getSuggestion()).isEqualTo("请检查网络连接后重试");
    }

    @Test
    void isRuntimeException_subclass() {
        BusinessException ex = new BusinessException(ErrorCode.INTERNAL_ERROR);
        assertThat(ex).isInstanceOf(RuntimeException.class);
    }
}
