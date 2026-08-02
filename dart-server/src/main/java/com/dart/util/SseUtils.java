package com.dart.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;

/**
 * SSE 事件发送工具类。
 */
public class SseUtils {

    private static final Logger log = LoggerFactory.getLogger(SseUtils.class);

    private SseUtils() {
    }

    public static SseEmitter createEmitter(long timeoutMs) {
        return new SseEmitter(timeoutMs);
    }

    public static void sendEvent(SseEmitter emitter, String eventName, Object data) {
        try {
            emitter.send(SseEmitter.event().name(eventName).data(data));
        } catch (Exception e) {
            // emitter 可能已被客户端断开/超时关闭，发送失败不应中断主流程
            log.debug("SSE 事件发送失败: event={}", eventName, e);
        }
    }

    public static void sendStatus(SseEmitter emitter, String phase, String message) {
        sendEvent(emitter, SseEvents.STATUS, java.util.Map.of("phase", phase, "message", message));
    }

    public static void sendError(SseEmitter emitter, int code, String message) {
        sendEvent(emitter, SseEvents.ERROR, java.util.Map.of("code", code, "message", message));
    }

    public static void completeSafe(SseEmitter emitter) {
        try {
            emitter.complete();
        } catch (Exception e) {
            log.debug("SSE 完成时异常（可能已关闭）", e);
        }
    }

    public static void errorSafe(SseEmitter emitter, Throwable throwable) {
        try {
            emitter.completeWithError(throwable);
        } catch (Exception e) {
            log.debug("SSE 错误完成时异常", e);
        }
    }
}
