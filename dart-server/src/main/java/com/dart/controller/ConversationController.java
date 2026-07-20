package com.dart.controller;

import com.dart.dto.request.ConversationUpsertRequest;
import com.dart.dto.response.ApiResponse;
import com.dart.dto.response.ConversationVO;
import com.dart.dto.response.MessageVO;
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

    @GetMapping
    public ApiResponse<List<ConversationVO>> list() {
        Long userId = SecurityUtils.getCurrentUserId();
        return ApiResponse.ok(conversationService.listConversations(userId));
    }

    @PostMapping
    public ApiResponse<ConversationVO> create() {
        Long userId = SecurityUtils.getCurrentUserId();
        return ApiResponse.ok(conversationService.createConversation(userId));
    }

    @GetMapping("/{id}/messages")
    public ApiResponse<List<MessageVO>> getMessages(@PathVariable Long id) {
        Long userId = SecurityUtils.getCurrentUserId();
        return ApiResponse.ok(conversationService.getMessages(userId, id));
    }

    @PutMapping("/{id}")
    public ApiResponse<Void> update(@PathVariable Long id,
                                     @Valid @RequestBody ConversationUpsertRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        conversationService.updateConversation(userId, id, request);
        return ApiResponse.ok(null);
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        Long userId = SecurityUtils.getCurrentUserId();
        conversationService.deleteConversation(userId, id);
        return ApiResponse.ok(null);
    }
}
