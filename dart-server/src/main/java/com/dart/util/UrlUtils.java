package com.dart.util;

/**
 * URL 工具：尾斜杠规范化（API 基地址用户输入去首尾空白与尾部 /，兼容复制粘贴带入）。
 */
public final class UrlUtils {

    private UrlUtils() {
    }

    /** 去首尾空白与全部尾部斜杠；null 原样返回。 */
    public static String trimTrailingSlash(String url) {
        if (url == null) {
            return null;
        }
        return url.trim().replaceAll("/+$", "");
    }
}
