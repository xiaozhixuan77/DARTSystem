package com.dart.broadcast;

import com.dart.util.SseUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mockStatic;

class SessionBroadcasterTest {

    private SessionBroadcaster broadcaster;

    @BeforeEach
    void setUp() {
        broadcaster = new SessionBroadcaster(100L);
    }

    private static SseEmitter newEmitter() {
        return new SseEmitter(60_000L);
    }

    @Test
    void sendEvent_beforeSubscribe_accumulatesContentSnapshot() {
        try (MockedStatic<SseUtils> sse = mockStatic(SseUtils.class)) {
            broadcaster.sendEvent("content", "hello ");
            broadcaster.sendEvent("content", "world");

            SseEmitter emitter = newEmitter();
            broadcaster.subscribe(emitter);

            // 新订阅者收到内容快照（已推送全部内容，合并为一次 content）
            sse.verify(() -> SseUtils.sendEvent(eq(emitter), eq("content"), eq("hello world")));
        }
    }

    @Test
    void subscribe_newSubscriber_getsLiveIncrementsAfterSnapshot() {
        try (MockedStatic<SseUtils> sse = mockStatic(SseUtils.class)) {
            broadcaster.sendEvent("content", "part1");
            SseEmitter emitter = newEmitter();
            broadcaster.subscribe(emitter);

            broadcaster.sendEvent("content", "part2");

            // 快照 + 实时增量
            sse.verify(() -> SseUtils.sendEvent(eq(emitter), eq("content"), eq("part1")));
            sse.verify(() -> SseUtils.sendEvent(eq(emitter), eq("content"), eq("part2")));
        }
    }

    @Test
    void subscribe_newSubscriber_replaysStatusEvents() {
        try (MockedStatic<SseUtils> sse = mockStatic(SseUtils.class)) {
            broadcaster.sendStatus("searching", "正在搜索...");
            broadcaster.sendEvent("content", "hello");

            SseEmitter emitter = newEmitter();
            broadcaster.subscribe(emitter);

            // 状态事件重放 + 内容快照
            sse.verify(() -> SseUtils.sendEvent(eq(emitter), eq("status"),
                    eq(Map.of("phase", "searching", "message", "正在搜索..."))));
            sse.verify(() -> SseUtils.sendEvent(eq(emitter), eq("content"), eq("hello")));
        }
    }

    @Test
    void sendEvent_broadcastsToAllSubscribers() {
        try (MockedStatic<SseUtils> sse = mockStatic(SseUtils.class)) {
            SseEmitter e1 = newEmitter();
            SseEmitter e2 = newEmitter();
            broadcaster.subscribe(e1);
            broadcaster.subscribe(e2);

            broadcaster.sendEvent("content", "delta");

            sse.verify(() -> SseUtils.sendEvent(eq(e1), eq("content"), eq("delta")));
            sse.verify(() -> SseUtils.sendEvent(eq(e2), eq("content"), eq("delta")));
        }
    }

    @Test
    void subscriberDisconnect_removedWithoutAffectingOthers() {
        try (MockedStatic<SseUtils> sse = mockStatic(SseUtils.class)) {
            SseEmitter e1 = org.mockito.Mockito.spy(new SseEmitter(60_000L));
            SseEmitter e2 = newEmitter();
            broadcaster.subscribe(e1);
            broadcaster.subscribe(e2);

            // 单元环境无容器触发回调，手动执行 subscribe 注册的 onError 回调（模拟客户端断连）
            org.mockito.ArgumentCaptor<java.util.function.Consumer<Throwable>> errCaptor =
                    org.mockito.ArgumentCaptor.forClass(java.util.function.Consumer.class);
            org.mockito.Mockito.verify(e1).onError(errCaptor.capture());
            errCaptor.getValue().accept(new RuntimeException("client gone"));

            broadcaster.sendEvent("content", "after");

            // 断连者不再收到，其余订阅者正常
            sse.verify(() -> SseUtils.sendEvent(eq(e1), eq("content"), eq("after")),
                    org.mockito.Mockito.never());
            sse.verify(() -> SseUtils.sendEvent(eq(e2), eq("content"), eq("after")));
        }
    }

    @Test
    void complete_completesAllSubscribers() {
        try (MockedStatic<SseUtils> sse = mockStatic(SseUtils.class)) {
            SseEmitter e1 = newEmitter();
            SseEmitter e2 = newEmitter();
            broadcaster.subscribe(e1);
            broadcaster.subscribe(e2);

            broadcaster.complete();

            sse.verify(() -> SseUtils.completeSafe(e1));
            sse.verify(() -> SseUtils.completeSafe(e2));
        }
    }

    @Test
    void subscribe_afterComplete_skipsSnapshotAndCompletes() {
        try (MockedStatic<SseUtils> sse = mockStatic(SseUtils.class)) {
            broadcaster.sendEvent("content", "hello");
            broadcaster.complete();

            SseEmitter emitter = newEmitter();
            broadcaster.subscribe(emitter);

            // completed 后订阅：不重放快照，直接 complete（done 事件已由生成方广播）
            sse.verify(() -> SseUtils.sendEvent(eq(emitter), eq("content"), org.mockito.ArgumentMatchers.anyString()),
                    org.mockito.Mockito.never());
            sse.verify(() -> SseUtils.completeSafe(emitter));
        }
    }

    @Test
    void sendEvent_afterComplete_noOp() {
        try (MockedStatic<SseUtils> sse = mockStatic(SseUtils.class)) {
            SseEmitter emitter = newEmitter();
            broadcaster.subscribe(emitter);
            broadcaster.complete();

            broadcaster.sendEvent("content", "late");

            sse.verify(() -> SseUtils.sendEvent(eq(emitter), eq("content"), org.mockito.ArgumentMatchers.anyString()),
                    org.mockito.Mockito.never());
        }
    }

    @Test
    void subscribe_replaysSnapshotExactlyOnce() {
        try (MockedStatic<SseUtils> sse = mockStatic(SseUtils.class)) {
            broadcaster.sendStatus("searching", "正在搜索...");
            broadcaster.sendEvent("content", "hello ");
            SseEmitter emitter = newEmitter();
            broadcaster.subscribe(emitter);

            // 快照先行 + 入 map 后实时转发：状态与内容均只重放一次（防 put-重放窗口内重复）
            sse.verify(() -> SseUtils.sendEvent(eq(emitter), eq("content"), eq("hello ")),
                    org.mockito.Mockito.times(1));
            sse.verify(() -> SseUtils.sendEvent(eq(emitter), eq("status"),
                    eq(Map.of("phase", "searching", "message", "正在搜索..."))),
                    org.mockito.Mockito.times(1));
        }
    }

    @Test
    void sendError_accumulatesForReplayAndBroadcasts() {
        try (MockedStatic<SseUtils> sse = mockStatic(SseUtils.class)) {
            SseEmitter e1 = newEmitter();
            broadcaster.subscribe(e1);

            broadcaster.sendError(500, "服务内部错误");

            sse.verify(() -> SseUtils.sendEvent(eq(e1), eq("error"),
                    eq(Map.of("code", 500, "message", "服务内部错误"))));

            // 新订阅者重放 error 事件
            SseEmitter e2 = newEmitter();
            broadcaster.subscribe(e2);
            sse.verify(() -> SseUtils.sendEvent(eq(e2), eq("error"),
                    eq(Map.of("code", 500, "message", "服务内部错误"))));
        }
    }
}
