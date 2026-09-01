package com.dart.exception;

public enum ErrorCode {

    // ===== 通用 (1xxx) =====
    BAD_REQUEST(400, "请求参数错误"),
    UNAUTHORIZED(401, "未登录或 Token 已过期"),
    FORBIDDEN(403, "无权访问"),

    // ===== 搜索 (3xxx) =====
    NON_TECH_QUERY(3001, "仅支持技术/编程领域搜索"),
    FETCH_PAGE_ERROR(3005, "页面抓取失败"),
    NO_QUALITY_RESULTS(3006, "未找到高质量资源，请尝试更换关键词"),

    // ===== Agent (4xxx) =====
    AGENT_EXECUTION_ERROR(4001, "Agent 执行异常"),

    // ===== 知识库 (5xxx) =====
    KNOWLEDGE_NOT_FOUND(5001, "知识条目不存在"),

    // ===== 会话 (6xxx) =====
    CONVERSATION_NOT_FOUND(6001, "会话不存在"),
    ;

    private final int code;
    private final String message;

    ErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    public int getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }
}
