package com.dart.controller;

import com.dart.dto.request.KnowledgeUpdateRequest;
import com.dart.dto.response.ApiResponse;
import com.dart.dto.response.KnowledgeDetailVO;
import com.dart.dto.response.KnowledgeGraphDTO;
import com.dart.dto.response.KnowledgeItemVO;
import com.dart.dto.response.PageResult;
import com.dart.service.KnowledgeService;
import com.dart.util.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/knowledge")
@RequiredArgsConstructor
public class KnowledgeController {

    private final KnowledgeService knowledgeService;

    @GetMapping
    public ApiResponse<PageResult<KnowledgeItemVO>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String tag,
            @RequestParam(required = false) String keyword) {
        Long userId = SecurityUtils.getCurrentUserId();
        return ApiResponse.ok(knowledgeService.listUserKnowledge(userId, page, size, tag, keyword));
    }

    @GetMapping("/tags")
    public ApiResponse<List<String>> tags() {
        Long userId = SecurityUtils.getCurrentUserId();
        return ApiResponse.ok(knowledgeService.aggregateTags(userId));
    }

    @PutMapping("/{id}")
    public ApiResponse<Void> update(@PathVariable Long id,
                                    @RequestBody KnowledgeUpdateRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        knowledgeService.updateKnowledge(userId, id, request);
        return ApiResponse.ok(null);
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

    @GetMapping("/{id}/graph")
    public ApiResponse<KnowledgeGraphDTO> graph(@PathVariable Long id) {
        Long userId = SecurityUtils.getCurrentUserId();
        return ApiResponse.ok(knowledgeService.getGraphData(userId, id));
    }

    /**
     * 手动补建知识条目间关联（双通道：标题子串 + 向量相似度；幂等）。
     */
    @PostMapping("/rebuild-links")
    public ApiResponse<Integer> rebuildLinks() {
        Long userId = SecurityUtils.getCurrentUserId();
        return ApiResponse.ok(knowledgeService.rebuildLinks(userId));
    }
}
