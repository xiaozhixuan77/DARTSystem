package com.dart.controller;

import com.dart.dto.request.ChatRequest;
import com.dart.service.ChatService;
import com.dart.util.SecurityUtils;
import com.dart.util.SseEvents;
import com.dart.util.SseUtils;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 聊天 SSE 流式端点。
 */
@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;

    @PostMapping(value = "/send", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter send(@Valid @RequestBody ChatRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        SseEmitter emitter = SseUtils.createEmitter(300_000L);
        AtomicBoolean completed = new AtomicBoolean(false);

        emitter.onCompletion(() -> completed.set(true));
        emitter.onTimeout(() -> completed.set(true));
        emitter.onError(e -> completed.set(true));

        Thread.startVirtualThread(() -> {
            try {
                ChatService.SearchResult result = chatService.executeSearchAndCurate(
                        request.getMessage(), userId, emitter);

                if (completed.get()) return;

                if (result.isRagHit()) {
                    SseUtils.sendEvent(emitter, SseEvents.CONTENT, result.ragAnswer().answer());
                } else if (result.curatedReport() != null) {
                    SseUtils.sendEvent(emitter, SseEvents.CONTENT,
                            result.curatedReport().markdown());
                }

                if (!completed.get()) {
                    SseUtils.sendEvent(emitter, SseEvents.DONE, Map.of("status", "complete"));
                    SseUtils.completeSafe(emitter);
                }
            } catch (Exception e) {
                if (!completed.get()) {
                    SseUtils.sendError(emitter, 500, "服务内部错误");
                    SseUtils.errorSafe(emitter, e);
                }
            }
        });

        return emitter;
    }
}
