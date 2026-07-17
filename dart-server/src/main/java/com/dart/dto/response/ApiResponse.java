package com.dart.dto.response;

public class ApiResponse<T> {

    private int code;
    private String message;
    private String suggestion;
    private T data;

    private ApiResponse(int code, String message, String suggestion, T data) {
        this.code = code;
        this.message = message;
        this.suggestion = suggestion;
        this.data = data;
    }

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(200, "ok", null, data);
    }

    public static <T> ApiResponse<T> error(int code, String message, String suggestion) {
        return new ApiResponse<>(code, message, suggestion, null);
    }

    public int getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }

    public String getSuggestion() {
        return suggestion;
    }

    public T getData() {
        return data;
    }
}
