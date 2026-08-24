package com.dart.broadcast;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 会话级 SSE 广播器：一次生成可被多个订阅者订阅（首个 send 连接 + 刷新后的 resume 连接）。
 * 新订阅者加入时重放已推送内容快照与状态序列，随后接收实时增量，直至生成完成。
 */
public class SessionBroadcaster implements SseSink {

    public SessionBroadcaster(Long conversationId) {
    }

    public void subscribe(SseEmitter emitter) {
    }

    @Override
    public void sendEvent(String eventName, Object data) {
    }

    @Override
    public void sendStatus(String phase, String message) {
    }

    @Override
    public void sendError(int code, String message) {
    }

    @Override
    public void complete() {
    }

    @Override
    public void completeWithError(Throwable t) {
    }
}
