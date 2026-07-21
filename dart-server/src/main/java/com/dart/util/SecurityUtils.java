package com.dart.util;

import com.dart.security.UserPrincipal;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

public class SecurityUtils {

    private SecurityUtils() {
    }

    public static Long getCurrentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof UserPrincipal)) {
            throw new org.springframework.security.core.AuthenticationException("未登录或 Token 无效") {};
        }
        return ((UserPrincipal) authentication.getPrincipal()).getUserId();
    }
}
