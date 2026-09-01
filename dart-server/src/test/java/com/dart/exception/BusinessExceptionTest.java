package com.dart.exception;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BusinessExceptionTest {

    @Test
    void constructor_withErrorCodeOnly() {
        BusinessException ex = new BusinessException(ErrorCode.FORBIDDEN);

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN);
        assertThat(ex.getMessage()).isEqualTo("无权访问");
        assertThat(ex.getSuggestion()).isNull();
    }

    @Test
    void constructor_withErrorCodeAndSuggestion() {
        BusinessException ex = new BusinessException(ErrorCode.NO_QUALITY_RESULTS, "请更换关键词后重试");

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.NO_QUALITY_RESULTS);
        assertThat(ex.getMessage()).isEqualTo("未找到高质量资源，请尝试更换关键词");
        assertThat(ex.getSuggestion()).isEqualTo("请更换关键词后重试");
    }

    @Test
    void isRuntimeException_subclass() {
        BusinessException ex = new BusinessException(ErrorCode.AGENT_EXECUTION_ERROR);
        assertThat(ex).isInstanceOf(RuntimeException.class);
    }
}
