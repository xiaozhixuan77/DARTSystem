package com.dart.controller;

import com.dart.broadcast.BroadcasterRegistry;
import com.dart.broadcast.SessionBroadcaster;
import com.dart.dto.request.ChatRequest;
import com.dart.dto.request.ConversationUpsertRequest;
import com.dart.dto.request.ResumeRequest;
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

@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;
    private final ConversationService conversationService;
    private final BroadcasterRegistry broadcasterRegistry;

    @PostMapping(value = "/send", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter send(@Valid @RequestBody ChatRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        SseEmitter emitter = SseUtils.createEmitter(180_000L);

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
        } else {
            // 会话归属校验（非本人会话抛 FORBIDDEN/NOT_FOUND，由 GlobalExceptionHandler 处理）
            conversationService.getConversation(userId, conversationId);
        }

        final Long finalConversationId = conversationId;

        // 会话级广播器：首个订阅者（本连接）+ 刷新后的 resume 订阅者共享同一生成流，
        // 断连不影响生成，done 始终广播（无订阅者时发送失败被吞，无害）
        SessionBroadcaster broadcaster = broadcasterRegistry.register(finalConversationId);
        broadcaster.subscribe(emitter);

        Thread.startVirtualThread(() -> {
            try {
                // 提前告知前端会话 id，SSE 中断时前端可据此拉取已生成的消息
                broadcaster.sendStatus("conversation", String.valueOf(finalConversationId));

                ChatService.SearchResult result = chatService.executeSearchAndCurate(
                        request.getMessage(), userId, finalConversationId, broadcaster);

                // CONTENT 事件统一由 ChatService 内部发送（分节流式/整块），此处只发完成信号
                broadcaster.sendEvent(SseEvents.DONE,
                        Map.of("status", "complete", "conversationId", finalConversationId));
            } catch (Exception e) {
                broadcaster.sendError(500, "服务内部错误");
            } finally {
                // 正常与异常路径都清理注册表，防泄漏
                broadcaster.complete();
                broadcasterRegistry.remove(finalConversationId);
            }
        });

        return emitter;
    }

    /**
     * 恢复续流：刷新后重新订阅正在生成的会话，从已生成位置继续接收 SSE 增量。
     * 生成中（注册表有广播器）→ 快照重放 + 实时增量；已结束/重启后注册表丢失 →
     * 立即返回 done(completed)，前端据此重载消息（草稿或完整）兜底。
     */
    @PostMapping(value = "/resume", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter resume(@Valid @RequestBody ResumeRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        // 会话归属校验（跨用户订阅拒绝，FORBIDDEN 由 GlobalExceptionHandler 处理）
        conversationService.getConversation(userId, request.getConversationId());

        SseEmitter emitter = SseUtils.createEmitter(180_000L);
        SessionBroadcaster broadcaster = broadcasterRegistry.get(request.getConversationId());
        if (broadcaster != null) {
            broadcaster.subscribe(emitter);
        } else {
            SseUtils.sendEvent(emitter, SseEvents.DONE,
                    Map.of("status", "completed", "conversationId", request.getConversationId()));
            SseUtils.completeSafe(emitter);
        }
        return emitter;
    }
}
