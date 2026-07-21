package com.dart.controller;

import com.dart.dto.request.ChatRequest;
import com.dart.dto.response.ApiResponse;
import com.dart.service.ChatService;
import com.dart.util.SecurityUtils;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;

/**
 * 聊天 SSE 流式端点。
 */
@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;

    /**
     * SSE 流式聊天端点。
     */
    @PostMapping(value = "/send", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter send(@Valid @RequestBody ChatRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        SseEmitter emitter = new SseEmitter(120_000L);

        Thread.startVirtualThread(() -> {
            try {
                // RAG 或 Search+Curate 流程
                ChatService.SearchResult result = chatService.executeSearchAndCurate(
                        request.getMessage(), userId);

                if (result.ragHit()) {
                    var ragResult = result.ragAnswer().get();
                    emitter.send(SseEmitter.event()
                            .name("status")
                            .data("{\"phase\":\"rag_hit\",\"message\":\"知识库命中\"}"));
                    emitter.send(SseEmitter.event()
                            .name("content")
                            .data(ragResult.answer()));
                } else if (result.curatedReport().isPresent()) {
                    var report = result.curatedReport().get();
                    emitter.send(SseEmitter.event()
                            .name("content")
                            .data(report.markdown()));
                }

                emitter.send(SseEmitter.event()
                        .name("done")
                        .data("{\"status\":\"complete\"}"));
                emitter.complete();
            } catch (Exception e) {
                try {
                    emitter.send(SseEmitter.event()
                            .name("error")
                            .data("{\"code\":500,\"message\":\"服务内部错误\"}"));
                    emitter.completeWithError(e);
                } catch (IOException ex) {
                    emitter.completeWithError(ex);
                }
            }
        });

        return emitter;
    }
}
