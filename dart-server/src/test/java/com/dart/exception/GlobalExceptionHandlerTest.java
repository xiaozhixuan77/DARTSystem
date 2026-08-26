package com.dart.exception;

import com.dart.dto.response.ApiResponse;
import jakarta.validation.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void handleBusinessException_returnsErrorCode() {
        ApiResponse<Void> resp = handler.handleBusinessException(
                new BusinessException(ErrorCode.FORBIDDEN));
        assertThat(resp.getCode()).isEqualTo(ErrorCode.FORBIDDEN.getCode());
    }

    @Test
    void handleUnreadableBody_returns400() {
        ApiResponse<Void> resp = handler.handleBadRequest(new HttpMessageNotReadableException("body"));
        assertThat(resp.getCode()).isEqualTo(400);
        assertThat(resp.getMessage()).contains("参数错误");
    }

    @Test
    void handleTypeMismatch_returns400() {
        ApiResponse<Void> resp = handler.handleBadRequest(new MethodArgumentTypeMismatchException(
                "page", Integer.class, "abc", null, null));
        assertThat(resp.getCode()).isEqualTo(400);
    }

    @Test
    void handleConstraintViolation_returns400() {
        ApiResponse<Void> resp = handler.handleBadRequest(new ConstraintViolationException("violated", null));
        assertThat(resp.getCode()).isEqualTo(400);
    }

    @Test
    void handleMissingParameter_returns400() {
        ApiResponse<Void> resp = handler.handleBadRequest(
                new MissingServletRequestParameterException("page", "int"));
        assertThat(resp.getCode()).isEqualTo(400);
    }

    @Test
    void handleMethodNotSupported_returns405() {
        ApiResponse<Void> resp = handler.handleMethodNotSupported(
                new HttpRequestMethodNotSupportedException("DELETE"));
        assertThat(resp.getCode()).isEqualTo(405);
        assertThat(resp.getMessage()).contains("DELETE");
    }
}
