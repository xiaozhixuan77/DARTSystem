package com.dart.exception;

public enum ErrorCode {

    // ===== 通用 (1xxx) =====
    SUCCESS(200, "ok"),
    BAD_REQUEST(400, "请求参数错误"),
    UNAUTHORIZED(401, "未登录或 Token 已过期"),
    FORBIDDEN(403, "无权访问"),
    NOT_FOUND(404, "资源不存在"),
    INTERNAL_ERROR(500, "服务内部错误"),

    // ===== 认证 (2xxx) =====
    USERNAME_EXISTS(2001, "用户名已存在"),
    USERNAME_INVALID(2002, "用户名格式不合法（3-20位字母数字下划线）"),
    PASSWORD_TOO_SHORT(2003, "密码长度不足6位"),
    LOGIN_FAILED(2004, "用户名或密码错误"),
    TOKEN_EXPIRED(2005, "Token 已过期，请重新登录"),
    TOKEN_INVALID(2006, "Token 无效"),
    REFRESH_TOKEN_EXPIRED(2007, "Refresh Token 已过期，请重新登录"),

    // ===== 搜索 (3xxx) =====
    NON_TECH_QUERY(3001, "仅支持技术/编程领域搜索"),
    SEARCH_TIMEOUT(3002, "搜索引擎响应超时，请稍后重试"),
    SEARCH_ERROR(3003, "搜索引擎服务异常"),
    FETCH_PAGE_TIMEOUT(3004, "页面抓取超时"),
    FETCH_PAGE_ERROR(3005, "页面抓取失败"),
    NO_QUALITY_RESULTS(3006, "未找到高质量资源，请尝试更换关键词"),

    // ===== Agent (4xxx) =====
    AGENT_EXECUTION_ERROR(4001, "Agent 执行异常"),
    LLM_API_ERROR(4002, "大模型 API 调用失败"),
    LLM_API_TIMEOUT(4003, "大模型 API 响应超时"),
    EMBEDDING_API_ERROR(4004, "Embedding API 调用失败"),
    SCORING_ERROR(4005, "资源评分计算异常"),

    // ===== 知识库 (5xxx) =====
    KNOWLEDGE_NOT_FOUND(5001, "知识条目不存在"),
    CHUNK_ERROR(5002, "文本分块处理异常"),
    VECTOR_WRITE_ERROR(5003, "向量写入 Redis 失败"),
    REBUILD_IN_PROGRESS(5004, "向量索引重建中，请稍后再试"),

    // ===== 会话 (6xxx) =====
    CONVERSATION_NOT_FOUND(6001, "会话不存在"),
    CONVERSATION_LIMIT_EXCEEDED(6002, "会话数量已达上限（50条）"),
    MESSAGE_SAVE_ERROR(6003, "消息保存失败"),
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
