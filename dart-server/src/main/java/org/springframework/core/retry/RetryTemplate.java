package org.springframework.core.retry;

/**
 * Spring AI 2.0.0 兼容性桥接 —— Spring Framework 7.x 的 RetryTemplate 在 6.x 中不存在。
 * 提供最小实现以满足 {@code DeepSeekChatModel} 和 {@code SpringAiRetryAutoConfiguration} 的类引用。
 * 升级到 Spring Boot 4.x / Spring AI 3.x 后可删除此包。
 */
public class RetryTemplate {

    private RetryPolicy retryPolicy;

    public RetryTemplate() {}

    public RetryTemplate(RetryPolicy retryPolicy) {
        this.retryPolicy = retryPolicy;
    }

    public void setRetryListener(RetryListener listener) {
        // 开发模式下的最小实现，不执行实际重试逻辑
    }
}
