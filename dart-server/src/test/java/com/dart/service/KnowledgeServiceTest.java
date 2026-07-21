package com.dart.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.dart.entity.KnowledgeChunk;
import com.dart.entity.KnowledgeItem;
import com.dart.mapper.KnowledgeChunkMapper;
import com.dart.mapper.KnowledgeItemMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KnowledgeServiceTest {

    @Mock
    private KnowledgeItemMapper knowledgeItemMapper;
    @Mock
    private KnowledgeChunkMapper knowledgeChunkMapper;

    private KnowledgeService knowledgeService;

    @BeforeEach
    void setUp() {
        knowledgeService = new KnowledgeService(knowledgeItemMapper, knowledgeChunkMapper);
    }

    private KnowledgeItem createItem(Long id, Long userId, String title) {
        KnowledgeItem item = new KnowledgeItem();
        item.setId(id);
        item.setUserId(userId);
        item.setTitle(title);
        item.setSourceUrl("https://example.com");
        item.setScoreT(5);
        item.setScoreA(4);
        item.setScoreD(3);
        item.setScoreM(4);
        item.setWeightedScore(new BigDecimal("4.00"));
        item.setIsDeleted(0);
        item.setCreatedAt(LocalDateTime.now());
        item.setUpdatedAt(LocalDateTime.now());
        return item;
    }

    @Test
    void listUserKnowledge_returnsList() {
        when(knowledgeItemMapper.selectList(any(QueryWrapper.class)))
                .thenReturn(List.of(createItem(1L, 1L, "Spring Guide")));

        var result = knowledgeService.listUserKnowledge(1L, 1, 10);
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getTitle()).isEqualTo("Spring Guide");
    }

    @Test
    void getKnowledgeDetail_returnsDetail() {
        KnowledgeItem item = createItem(1L, 1L, "Spring Guide");
        item.setContent("Full content here");
        when(knowledgeItemMapper.selectById(1L)).thenReturn(item);
        when(knowledgeChunkMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of());

        var result = knowledgeService.getKnowledgeDetail(1L, 1L);
        assertThat(result).isNotNull();
        assertThat(result.getTitle()).isEqualTo("Spring Guide");
    }

    @Test
    void getKnowledgeDetail_notFound_throwsException() {
        when(knowledgeItemMapper.selectById(99L)).thenReturn(null);
        assertThatThrownBy(() -> knowledgeService.getKnowledgeDetail(1L, 99L))
                .hasMessageContaining("不存在");
    }

    @Test
    void getKnowledgeDetail_notOwned_throwsException() {
        KnowledgeItem item = createItem(1L, 2L, "Others");
        when(knowledgeItemMapper.selectById(1L)).thenReturn(item);
        assertThatThrownBy(() -> knowledgeService.getKnowledgeDetail(1L, 1L))
                .hasMessageContaining("不存在");
    }

    @Test
    void deleteKnowledge_success() {
        KnowledgeItem item = createItem(1L, 1L, "Spring Guide");
        when(knowledgeItemMapper.selectById(1L)).thenReturn(item);
        when(knowledgeItemMapper.updateKnowledgeItem(any(KnowledgeItem.class))).thenReturn(1);
        knowledgeService.deleteKnowledge(1L, 1L);
    }

    @Test
    void existsBySourceUrl_found() {
        when(knowledgeItemMapper.selectCount(any(QueryWrapper.class))).thenReturn(1L);
        assertThat(knowledgeService.existsBySourceUrl("abc123")).isTrue();
    }

    @Test
    void existsBySourceUrl_notFound() {
        when(knowledgeItemMapper.selectCount(any(QueryWrapper.class))).thenReturn(0L);
        assertThat(knowledgeService.existsBySourceUrl("unknown")).isFalse();
    }
}
