package com.dart.broadcast;

/**
 * SSE 推送目标抽象：一次生成的编排链（ChatService/SearchAgent/CuratorAgent/CuratorTools）
 * 只依赖此接口推送事件，具体实现可为单订阅者（EmitterSink）或会话级广播器
 * （SessionBroadcaster，支持刷新后重新订阅续流）。
 */
public interface SseSink {

    void sendEvent(String eventName, Object data);

    void sendStatus(String phase, String message);

    void sendError(int code, String message);

    void complete();

    void completeWithError(Throwable t);
}
