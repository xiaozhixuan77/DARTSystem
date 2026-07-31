package com.dart.controller;

import com.dart.dto.response.ApiResponse;
import com.dart.dto.response.KnowledgeDetailVO;
import com.dart.dto.response.KnowledgeItemVO;
import com.dart.dto.response.PageResult;
import com.dart.service.KnowledgeService;
import com.dart.util.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/knowledge")
@RequiredArgsConstructor
public class KnowledgeController {

    private final KnowledgeService knowledgeService;

    @GetMapping
    public ApiResponse<PageResult<KnowledgeItemVO>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        Long userId = SecurityUtils.getCurrentUserId();
        return ApiResponse.ok(knowledgeService.listUserKnowledge(userId, page, size));
    }

    @GetMapping("/{id}")
    public ApiResponse<KnowledgeDetailVO> detail(@PathVariable Long id) {
        Long userId = SecurityUtils.getCurrentUserId();
        return ApiResponse.ok(knowledgeService.getKnowledgeDetail(userId, id));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        Long userId = SecurityUtils.getCurrentUserId();
        knowledgeService.deleteKnowledge(userId, id);
        return ApiResponse.ok(null);
    }
}
