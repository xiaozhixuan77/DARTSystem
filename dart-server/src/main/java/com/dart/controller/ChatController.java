package com.dart.controller;

import com.dart.dto.request.ChatRequest;
import com.dart.dto.request.ConversationUpsertRequest;
import com.dart.dto.response.ConversationVO;
import com.dart.service.ChatService;
import com.dart.service.ConversationService;
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

@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;
    private final ConversationService conversationService;

    @PostMapping(value = "/send", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter send(@Valid @RequestBody ChatRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        SseEmitter emitter = SseUtils.createEmitter(600_000L);
        AtomicBoolean completed = new AtomicBoolean(false);

        // 处理 conversationId：为 null 时自动创建新会话
        Long conversationId = request.getConversationId();
        if (conversationId == null) {
            String title = request.getMessage().length() > 50
                    ? request.getMessage().substring(0, 50)
                    : request.getMessage();
            ConversationUpsertRequest convReq = new ConversationUpsertRequest();
            convReq.setTitle(title);
            ConversationVO newConv = conversationService.createConversation(userId, convReq);
            conversationId = newConv.getId();
        }

        final Long finalConversationId = conversationId;

        emitter.onCompletion(() -> completed.set(true));
        emitter.onTimeout(() -> completed.set(true));
        emitter.onError(e -> completed.set(true));

        Thread.startVirtualThread(() -> {
            try {
                // 提前告知前端会话 id，SSE 中断时前端可据此拉取已生成的消息
                SseUtils.sendStatus(emitter, "conversation", String.valueOf(finalConversationId));

                ChatService.SearchResult result = chatService.executeSearchAndCurate(
                        request.getMessage(), userId, finalConversationId, emitter);

                if (completed.get()) return;

                // CONTENT 事件统一由 ChatService 内部发送（分节流式/整块），此处只发完成信号
                if (!completed.get()) {
                    SseUtils.sendEvent(emitter, SseEvents.DONE,
                            Map.of("status", "complete", "conversationId", finalConversationId));
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
