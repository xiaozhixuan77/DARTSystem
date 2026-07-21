package com.dart.util;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SseUtilsTest {

    private SseEmitter emitter;

    @BeforeEach
    void setUp() {
        emitter = new SseEmitter(120_000L);
    }

    @Test
    void createEmitter_returnsNonNullWithTimeout() {
        SseEmitter e = SseUtils.createEmitter(60_000L);
        assertThat(e).isNotNull();
        assertThat(e.getTimeout()).isEqualTo(60_000L);
    }

    @Test
    void sendEvent_pushesNamedEvent() throws IOException {
        SseEmitter spyEmitter = spy(new SseEmitter(60_000L));
        SseUtils.sendEvent(spyEmitter, "status", "searching");
        verify(spyEmitter).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    void sendError_pushesErrorEvent() throws IOException {
        SseEmitter spyEmitter = spy(new SseEmitter(60_000L));
        SseUtils.sendError(spyEmitter, 3001, "仅支持技术领域搜索");
        verify(spyEmitter).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    void completeSafe_whenAlreadyCompleted_doesNotThrow() {
        emitter.complete();
        assertThatCode(() -> SseUtils.completeSafe(emitter)).doesNotThrowAnyException();
    }
}
