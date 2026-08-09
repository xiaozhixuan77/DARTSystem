package com.dart.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dart.dto.response.KnowledgeGraphDTO;
import com.dart.entity.KnowledgeChunk;
import com.dart.entity.KnowledgeItem;
import com.dart.entity.KnowledgeRelation;
import com.dart.mapper.KnowledgeChunkMapper;
import com.dart.mapper.KnowledgeItemMapper;
import com.dart.mapper.KnowledgeRelationMapper;
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
    @Mock
    private KnowledgeRelationMapper relationMapper;

    private KnowledgeService knowledgeService;

    @BeforeEach
    void setUp() {
        knowledgeService = new KnowledgeService(knowledgeItemMapper, knowledgeChunkMapper, relationMapper);
    }

    private static KnowledgeRelation relation(Long subjectId, String predicate, Long objectId, String objectLabel) {
        KnowledgeRelation r = new KnowledgeRelation();
        r.setSubjectId(subjectId);
        r.setPredicate(predicate);
        r.setObjectId(objectId);
        r.setObjectLabel(objectLabel);
        return r;
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
        Page<KnowledgeItem> mockPage = new Page<>(1, 10);
        mockPage.setRecords(List.of(createItem(1L, 1L, "Spring Guide")));
        when(knowledgeItemMapper.selectPage(any(Page.class), any(QueryWrapper.class)))
                .thenReturn(mockPage);

        var result = knowledgeService.listUserKnowledge(1L, 1, 10);
        assertThat(result.getRecords()).hasSize(1);
        assertThat(result.getRecords().get(0).getTitle()).isEqualTo("Spring Guide");
        assertThat(result.getTotal()).isEqualTo(0L);
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
                .hasMessageContaining("无权访问");
    }

    @Test
    void deleteKnowledge_success() {
        KnowledgeItem item = createItem(1L, 1L, "Spring Guide");
        when(knowledgeItemMapper.selectById(1L)).thenReturn(item);
        when(knowledgeItemMapper.deleteById(1L)).thenReturn(1);
        knowledgeService.deleteKnowledge(1L, 1L);
    }

    // === 知识图谱 ===

    @Test
    void getGraphData_returnsTwoLayerGraph() {
        when(knowledgeItemMapper.selectById(1L)).thenReturn(createItem(1L, 1L, "Spring Boot"));
        when(knowledgeItemMapper.selectById(2L)).thenReturn(createItem(2L, 1L, "Spring Framework"));
        when(knowledgeItemMapper.selectById(3L)).thenReturn(createItem(3L, 1L, "Spring Cloud"));

        KnowledgeRelation r1 = relation(1L, "extends", 2L, null);
        KnowledgeRelation r2 = relation(3L, "related_to", 1L, null);
        KnowledgeRelation r3 = relation(2L, "part_of", null, "Spring Data");
        // 层 1（中心 1 的关系）→ 层 2（一级节点 2、3 的关系）
        when(relationMapper.selectList(any())).thenReturn(List.of(r1, r2), List.of(r3), List.of());

        KnowledgeGraphDTO graph = knowledgeService.getGraphData(1L, 1L);

        assertThat(graph.nodes()).extracting(KnowledgeGraphDTO.GraphNode::id)
                .contains("item:1", "item:2", "item:3", "ext:Spring Data");
        assertThat(graph.nodes()).extracting(KnowledgeGraphDTO.GraphNode::label)
                .contains("Spring Boot", "Spring Framework", "Spring Cloud");
        assertThat(graph.edges()).hasSize(3);
        assertThat(graph.edges()).extracting(KnowledgeGraphDTO.GraphEdge::label)
                .containsExactlyInAnyOrder("extends", "related_to", "part_of");
    }

    @Test
    void getGraphData_otherUsersItem_throwsForbidden() {
        when(knowledgeItemMapper.selectById(1L)).thenReturn(createItem(1L, 2L, "Others"));
        assertThatThrownBy(() -> knowledgeService.getGraphData(1L, 1L))
                .hasMessageContaining("无权访问");
    }

    @Test
    void getGraphData_notFound_throwsException() {
        when(knowledgeItemMapper.selectById(99L)).thenReturn(null);
        assertThatThrownBy(() -> knowledgeService.getGraphData(1L, 99L))
                .hasMessageContaining("不存在");
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
