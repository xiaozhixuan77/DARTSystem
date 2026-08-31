package com.dart.util;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 当前数据池用户 id 解析：无登录模式，请求头 X-User-Id 优先（前端池切换），
 * 缺省回落 app.default-user-id。
 */
@Component
public class SecurityUtils {

    /** 无请求头/解析失败时的默认用户 id（@Value 注入覆盖初始值） */
    private static long defaultUserId = 3L;

    @Value("${app.default-user-id:3}")
    public void setDefaultUserId(long id) {
        SecurityUtils.defaultUserId = id;
    }

    public static Long getCurrentUserId() {
        ServletRequestAttributes attrs =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attrs != null) {
            String header = attrs.getRequest().getHeader("X-User-Id");
            if (header != null && !header.isBlank()) {
                try {
                    return Long.parseLong(header.trim());
                } catch (NumberFormatException ignored) {
                    // 非法值回落默认用户
                }
            }
        }
        return defaultUserId;
    }
}
