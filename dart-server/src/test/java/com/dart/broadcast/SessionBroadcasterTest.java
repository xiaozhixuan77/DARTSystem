package com.dart.broadcast;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SessionBroadcasterTest {

    private SessionBroadcaster broadcaster;

    /** 测试发射器：spy 捕获每次 send 的 data（builder 继承 Set<DataWithMediaType>） */
    private static final class TestEmitter {
        final SseEmitter emitter = spy(new SseEmitter(60_000L));
        final List<Object> sentData = new ArrayList<>();

        TestEmitter() throws IOException {
            doAnswer(inv -> {
                // SseEventBuilder 继承 DataWithMediaType（单条），getData() 即事件负载
                ResponseBodyEmitter.DataWithMediaType data = inv.getArgument(0);
                sentData.add(data.getData());
                return true;
            }).when(emitter).send(any(SseEmitter.SseEventBuilder.class));
        }
    }

    @BeforeEach
    void setUp() throws IOException {
        broadcaster = new SessionBroadcaster(100L);
    }

    @Test
    void sendEvent_beforeSubscribe_accumulatesContentSnapshot() throws IOException {
        broadcaster.sendEvent("content", "hello ");
        broadcaster.sendEvent("content", "world");

        TestEmitter t = new TestEmitter();
        broadcaster.subscribe(t.emitter);

        // 新订阅者收到内容快照（已推送全部内容，合并为一次 content）
        assertThat(t.sentData).containsExactly("hello world");
    }

    @Test
    void subscribe_newSubscriber_getsLiveIncrementsAfterSnapshot() throws IOException {
        broadcaster.sendEvent("content", "part1");
        TestEmitter t = new TestEmitter();
        broadcaster.subscribe(t.emitter);

        broadcaster.sendEvent("content", "part2");

        assertThat(t.sentData).containsExactly("part1", "part2");
    }

    @Test
    void subscribe_newSubscriber_replaysStatusEvents() throws IOException {
        broadcaster.sendStatus("searching", "正在搜索...");
        broadcaster.sendEvent("content", "hello");

        TestEmitter t = new TestEmitter();
        broadcaster.subscribe(t.emitter);

        // 状态事件重放 + 内容快照
        assertThat(t.sentData).containsExactly(
                Map.of("phase", "searching", "message", "正在搜索..."),
                "hello");
    }

    @Test
    void sendEvent_broadcastsToAllSubscribers() throws IOException {
        TestEmitter t1 = new TestEmitter();
        TestEmitter t2 = new TestEmitter();
        broadcaster.subscribe(t1.emitter);
        broadcaster.subscribe(t2.emitter);

        broadcaster.sendEvent("content", "delta");

        assertThat(t1.sentData).containsExactly("delta");
        assertThat(t2.sentData).containsExactly("delta");
    }

    @Test
    void subscriberDisconnect_removedWithoutAffectingOthers() throws IOException {
        TestEmitter t1 = new TestEmitter();
        TestEmitter t2 = new TestEmitter();
        broadcaster.subscribe(t1.emitter);
        broadcaster.subscribe(t2.emitter);

        // 触发 t1 的 onError 回调（模拟客户端断连）
        t1.emitter.completeWithError(new RuntimeException("client gone"));
        broadcaster.sendEvent("content", "after");

        assertThat(t1.sentData).isEmpty();
        assertThat(t2.sentData).containsExactly("after");
    }

    @Test
    void complete_completesAllSubscribers() throws IOException {
        TestEmitter t1 = new TestEmitter();
        TestEmitter t2 = new TestEmitter();
        broadcaster.subscribe(t1.emitter);
        broadcaster.subscribe(t2.emitter);

        broadcaster.complete();

        verify(t1.emitter).complete();
        verify(t2.emitter).complete();
    }

    @Test
    void subscribe_afterComplete_skipsSnapshotAndCompletes() throws IOException {
        broadcaster.sendEvent("content", "hello");
        broadcaster.complete();

        TestEmitter t = new TestEmitter();
        broadcaster.subscribe(t.emitter);

        // completed 后订阅：不重放快照，直接 complete（done 事件已由生成方广播）
        assertThat(t.sentData).isEmpty();
        verify(t.emitter).complete();
    }

    @Test
    void sendEvent_afterComplete_noOp() throws IOException {
        TestEmitter t = new TestEmitter();
        broadcaster.subscribe(t.emitter);
        broadcaster.complete();

        broadcaster.sendEvent("content", "late");

        assertThat(t.sentData).isEmpty();
    }

    @Test
    void sendError_accumulatesForReplayAndBroadcasts() throws IOException {
        TestEmitter t1 = new TestEmitter();
        broadcaster.subscribe(t1.emitter);

        broadcaster.sendError(500, "服务内部错误");

        assertThat(t1.sentData).containsExactly(Map.of("code", 500, "message", "服务内部错误"));

        // 新订阅者重放 error 事件
        TestEmitter t2 = new TestEmitter();
        broadcaster.subscribe(t2.emitter);
        assertThat(t2.sentData).containsExactly(Map.of("code", 500, "message", "服务内部错误"));
    }
}
