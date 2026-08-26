package com.dart.broadcast;

import com.dart.util.SseEvents;
import com.dart.util.SseUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 会话级 SSE 广播器：一次生成可被多个订阅者订阅（首个 send 连接 + 刷新后的 resume 连接）。
 * 新订阅者加入时重放已推送内容快照与状态事件序列，随后接收实时增量，直至生成完成。
 * 单订阅者断连（completeWithError/超时）只移除该订阅者，不影响生成与其他订阅者。
 */
public class SessionBroadcaster implements SseSink {

    private static final Logger log = LoggerFactory.getLogger(SessionBroadcaster.class);

    private final Long conversationId;
    private final ConcurrentHashMap<Long, SseEmitter> subscribers = new ConcurrentHashMap<>();
    /** 非 content 事件日志，供新订阅者重放；content 用 contentBuffer 快照 */
    private final List<LoggedEvent> eventLog = new CopyOnWriteArrayList<>();

    private record LoggedEvent(String eventName, Object data) {
    }
    /** 已推送内容快照（content 事件增量累积），新订阅者收到"截至当前的全部已推送内容" */
    private final StringBuilder contentBuffer = new StringBuilder();
    private final AtomicLong subscriberSeq = new AtomicLong();
    private volatile boolean completed;

    public SessionBroadcaster(Long conversationId) {
        this.conversationId = conversationId;
    }

    public Long getConversationId() {
        return conversationId;
    }

    public boolean isCompleted() {
        return completed;
    }

    /**
     * 新订阅者加入：重放状态事件序列 + content 快照，随后接收实时增量。
     * 已完成（与生成结束竞态）时直接 complete，不重放（done 事件已广播过）。
     */
    public void subscribe(SseEmitter emitter) {
        if (completed) {
            SseUtils.completeSafe(emitter);
            return;
        }
        long id = subscriberSeq.incrementAndGet();
        // 先取快照再入 map：put 与重放之间到达的事件走实时转发（入 map 后立即生效），
        // 若 put 前不取快照，该窗口事件会被转发 + 重放各一次造成重复
        List<LoggedEvent> snapshot = new ArrayList<>(eventLog);
        String contentSnapshot = contentBuffer.toString();
        subscribers.put(id, emitter);
        emitter.onCompletion(() -> subscribers.remove(id));
        emitter.onTimeout(() -> subscribers.remove(id));
        emitter.onError(e -> subscribers.remove(id));

        // 竞态防护：complete() 可能在"检查 completed 与 put 之间"执行并清空订阅者，
        // 此处复查——已完成的会话不重放，直接关闭本订阅者（避免悬挂至超时）
        if (completed) {
            subscribers.remove(id);
            SseUtils.completeSafe(emitter);
            return;
        }

        // 按时间序重放：状态事件 → 内容快照（已推送部分整块出现，后续增量实时转发）
        for (LoggedEvent event : snapshot) {
            SseUtils.sendEvent(emitter, event.eventName(), event.data());
        }
        if (!contentSnapshot.isEmpty()) {
            SseUtils.sendEvent(emitter, SseEvents.CONTENT, contentSnapshot);
        }
    }

    @Override
    public void sendEvent(String eventName, Object data) {
        if (completed) {
            return;
        }
        if (SseEvents.CONTENT.equals(eventName)) {
            if (data != null) {
                contentBuffer.append(data.toString());
            }
        } else {
            eventLog.add(new LoggedEvent(eventName, data));
        }
        for (SseEmitter emitter : subscribers.values()) {
            SseUtils.sendEvent(emitter, eventName, data);
        }
    }

    @Override
    public void sendStatus(String phase, String message) {
        sendEvent(SseEvents.STATUS, java.util.Map.of("phase", phase, "message", message));
    }

    @Override
    public void sendError(int code, String message) {
        sendEvent(SseEvents.ERROR, java.util.Map.of("code", code, "message", message));
    }

    @Override
    public void complete() {
        if (completed) {
            return;
        }
        completed = true;
        for (SseEmitter emitter : subscribers.values()) {
            SseUtils.completeSafe(emitter);
        }
        subscribers.clear();
    }

    @Override
    public void completeWithError(Throwable t) {
        if (completed) {
            return;
        }
        completed = true;
        for (SseEmitter emitter : subscribers.values()) {
            SseUtils.errorSafe(emitter, t);
        }
        subscribers.clear();
    }
}
