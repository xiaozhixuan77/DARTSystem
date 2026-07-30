package com.dart.controller;

import com.dart.dto.request.ConversationUpsertRequest;
import com.dart.dto.response.ApiResponse;
import com.dart.dto.response.ConversationVO;
import com.dart.dto.response.MessageVO;
import com.dart.exception.ErrorCode;
import com.dart.service.ConversationService;
import com.dart.util.SecurityUtils;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/conversations")
@RequiredArgsConstructor
public class ConversationController {

    private final ConversationService conversationService;

    private Long requireUserId() {
        Long userId = SecurityUtils.getCurrentUserId();
        if (userId == null) {
            throw new com.dart.exception.BusinessException(ErrorCode.UNAUTHORIZED);
        }
        return userId;
    }

    @GetMapping
    public ApiResponse<List<ConversationVO>> list() {
        return ApiResponse.ok(conversationService.listConversations(requireUserId()));
    }

    @PostMapping
    public ApiResponse<ConversationVO> create(@Valid @RequestBody ConversationUpsertRequest request) {
        return ApiResponse.ok(conversationService.createConversation(requireUserId(), request));
    }

    @GetMapping("/{id}")
    public ApiResponse<ConversationVO> getById(@PathVariable Long id) {
        return ApiResponse.ok(conversationService.getConversation(requireUserId(), id));
    }

    @GetMapping("/{id}/messages")
    public ApiResponse<List<MessageVO>> getMessages(@PathVariable Long id) {
        return ApiResponse.ok(conversationService.getMessages(requireUserId(), id));
    }

    @PutMapping("/{id}")
    public ApiResponse<Void> update(@PathVariable Long id,
                                     @Valid @RequestBody ConversationUpsertRequest request) {
        conversationService.updateConversation(requireUserId(), id, request);
        return ApiResponse.ok(null);
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        conversationService.deleteConversation(requireUserId(), id);
        return ApiResponse.ok(null);
    }
}
