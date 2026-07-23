package org.springframework.core.retry;

import java.time.Duration;

/**
 * Spring AI 2.0.0 兼容性桥接 —— Spring Framework 7.x 的 RetryPolicy 在 6.x 中不存在。
 */
public interface RetryPolicy {

    static Builder builder() {
        return new Builder() {
            private long maxRetries = 3;
            private Class<?>[] includes = new Class<?>[]{Exception.class};
            private Duration delay = Duration.ofSeconds(1);
            private double multiplier = 2.0;
            private Duration maxDelay = Duration.ofSeconds(30);

            @Override
            public Builder maxRetries(long maxRetries) { this.maxRetries = maxRetries; return this; }
            @Override
            public Builder includes(Class<?>... throwables) { this.includes = throwables; return this; }
            @Override
            public Builder delay(Duration delay) { this.delay = delay; return this; }
            @Override
            public Builder multiplier(double multiplier) { this.multiplier = multiplier; return this; }
            @Override
            public Builder maxDelay(Duration maxDelay) { this.maxDelay = maxDelay; return this; }
            @Override
            public RetryPolicy build() { return new SimpleRetryPolicy(); }
        };
    }

    interface Builder {
        Builder maxRetries(long maxRetries);
        Builder includes(Class<?>... throwables);
        Builder delay(Duration delay);
        Builder multiplier(double multiplier);
        Builder maxDelay(Duration maxDelay);
        RetryPolicy build();
    }

    /** 最小实现，不执行实际重试逻辑。 */
    final class SimpleRetryPolicy implements RetryPolicy {}
}
