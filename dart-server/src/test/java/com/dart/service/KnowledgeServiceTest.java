package com.dart.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dart.dto.request.KnowledgeUpdateRequest;
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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KnowledgeServiceTest {

    @Mock
    private KnowledgeItemMapper knowledgeItemMapper;
    @Mock
    private KnowledgeChunkMapper knowledgeChunkMapper;
    @Mock
    private KnowledgeRelationMapper relationMapper;
    @Mock
    private EmbeddingService embeddingService;

    private KnowledgeService knowledgeService;

    @BeforeEach
    void setUp() {
        knowledgeService = new KnowledgeService(knowledgeItemMapper, knowledgeChunkMapper,
                relationMapper, embeddingService);
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

        var result = knowledgeService.listUserKnowledge(1L, 1, 10, null, null);
        assertThat(result.getRecords()).hasSize(1);
        assertThat(result.getRecords().get(0).getTitle()).isEqualTo("Spring Guide");
        assertThat(result.getTotal()).isEqualTo(0L);
    }

    @Test
    void listUserKnowledge_withTag_appliesFindInSet() {
        Page<KnowledgeItem> mockPage = new Page<>(1, 10);
        mockPage.setRecords(List.of(createItem(1L, 1L, "Spring Guide")));
        when(knowledgeItemMapper.selectPage(any(Page.class), any(QueryWrapper.class)))
                .thenReturn(mockPage);

        knowledgeService.listUserKnowledge(1L, 1, 10, "Go", null);

        ArgumentCaptor<QueryWrapper<KnowledgeItem>> captor = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(knowledgeItemMapper).selectPage(any(Page.class), captor.capture());
        assertThat(captor.getValue().getCustomSqlSegment()).contains("FIND_IN_SET");
    }

    @Test
    void listUserKnowledge_withoutTag_noFindInSet() {
        Page<KnowledgeItem> mockPage = new Page<>(1, 10);
        mockPage.setRecords(List.of(createItem(1L, 1L, "Spring Guide")));
        when(knowledgeItemMapper.selectPage(any(Page.class), any(QueryWrapper.class)))
                .thenReturn(mockPage);

        knowledgeService.listUserKnowledge(1L, 1, 10, null, null);

        ArgumentCaptor<QueryWrapper<KnowledgeItem>> captor = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(knowledgeItemMapper).selectPage(any(Page.class), captor.capture());
        assertThat(captor.getValue().getCustomSqlSegment()).doesNotContain("FIND_IN_SET");
    }

    @Test
    void listUserKnowledge_withKeyword_appliesLikeAndExists() {
        Page<KnowledgeItem> mockPage = new Page<>(1, 10);
        mockPage.setRecords(List.of(createItem(1L, 1L, "Spring Guide")));
        when(knowledgeItemMapper.selectPage(any(Page.class), any(QueryWrapper.class)))
                .thenReturn(mockPage);

        knowledgeService.listUserKnowledge(1L, 1, 10, null, "Spring");

        ArgumentCaptor<QueryWrapper<KnowledgeItem>> captor = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(knowledgeItemMapper).selectPage(any(Page.class), captor.capture());
        assertThat(captor.getValue().getCustomSqlSegment()).contains("title LIKE");
        assertThat(captor.getValue().getCustomSqlSegment()).contains("EXISTS");
        assertThat(captor.getValue().getCustomSqlSegment()).contains("knowledge_chunk");
    }

    @Test
    void listUserKnowledge_withKeywordAndTag_combinesConditions() {
        Page<KnowledgeItem> mockPage = new Page<>(1, 10);
        mockPage.setRecords(List.of(createItem(1L, 1L, "Spring Guide")));
        when(knowledgeItemMapper.selectPage(any(Page.class), any(QueryWrapper.class)))
                .thenReturn(mockPage);

        knowledgeService.listUserKnowledge(1L, 1, 10, "Go", "Spring");

        ArgumentCaptor<QueryWrapper<KnowledgeItem>> captor = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(knowledgeItemMapper).selectPage(any(Page.class), captor.capture());
        assertThat(captor.getValue().getCustomSqlSegment()).contains("FIND_IN_SET");
        assertThat(captor.getValue().getCustomSqlSegment()).contains("title LIKE");
        assertThat(captor.getValue().getCustomSqlSegment()).contains("EXISTS");
    }

    @Test
    void listUserKnowledge_blankKeyword_noKeywordCondition() {
        Page<KnowledgeItem> mockPage = new Page<>(1, 10);
        mockPage.setRecords(List.of(createItem(1L, 1L, "Spring Guide")));
        when(knowledgeItemMapper.selectPage(any(Page.class), any(QueryWrapper.class)))
                .thenReturn(mockPage);

        knowledgeService.listUserKnowledge(1L, 1, 10, null, "   ");

        ArgumentCaptor<QueryWrapper<KnowledgeItem>> captor = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(knowledgeItemMapper).selectPage(any(Page.class), captor.capture());
        assertThat(captor.getValue().getCustomSqlSegment()).doesNotContain("LIKE");
        assertThat(captor.getValue().getCustomSqlSegment()).doesNotContain("EXISTS");
    }

    @Test
    void updateKnowledge_updatesFieldsWithNormalizedTags() {
        KnowledgeItem item = createItem(1L, 1L, "Spring Guide");
        when(knowledgeItemMapper.selectById(1L)).thenReturn(item);
        when(knowledgeItemMapper.updateById(any(KnowledgeItem.class))).thenReturn(1);

        knowledgeService.updateKnowledge(1L, 1L,
                new KnowledgeUpdateRequest("新标题", "Go, 并发, , Go,", "笔记", null));

        ArgumentCaptor<KnowledgeItem> captor = ArgumentCaptor.forClass(KnowledgeItem.class);
        verify(knowledgeItemMapper).updateById(captor.capture());
        assertThat(captor.getValue().getTitle()).isEqualTo("新标题");
        assertThat(captor.getValue().getTags()).isEqualTo("Go,并发");
        assertThat(captor.getValue().getNotes()).isEqualTo("笔记");
    }

    @Test
    void updateKnowledge_notFound_throwsException() {
        when(knowledgeItemMapper.selectById(99L)).thenReturn(null);
        assertThatThrownBy(() -> knowledgeService.updateKnowledge(1L, 99L,
                new KnowledgeUpdateRequest("新标题", "Go", "n", null)))
                .hasMessageContaining("不存在");
    }

    @Test
    void updateKnowledge_notOwned_throwsException() {
        when(knowledgeItemMapper.selectById(1L)).thenReturn(createItem(1L, 2L, "Others"));
        assertThatThrownBy(() -> knowledgeService.updateKnowledge(1L, 1L,
                new KnowledgeUpdateRequest("新标题", "Go", "n", null)))
                .hasMessageContaining("无权访问");
    }

    @Test
    void updateKnowledge_blankTitle_throwsException() {
        when(knowledgeItemMapper.selectById(1L)).thenReturn(createItem(1L, 1L, "x"));
        assertThatThrownBy(() -> knowledgeService.updateKnowledge(1L, 1L,
                new KnowledgeUpdateRequest("   ", "Go", "n", null)))
                .hasMessageContaining("请求参数错误");
    }

    @Test
    void updateKnowledge_titleTooLong_throwsException() {
        when(knowledgeItemMapper.selectById(1L)).thenReturn(createItem(1L, 1L, "x"));
        String longTitle = "a".repeat(501);
        assertThatThrownBy(() -> knowledgeService.updateKnowledge(1L, 1L,
                new KnowledgeUpdateRequest(longTitle, "Go", "n", null)))
                .hasMessageContaining("请求参数错误");
    }

    @Test
    void updateKnowledge_contentChanged_updatesContentDeletesOldChunksAndRebuilds() {
        KnowledgeItem item = createItem(1L, 1L, "Spring Guide");
        item.setContent("旧内容");
        when(knowledgeItemMapper.selectById(1L)).thenReturn(item);
        when(knowledgeItemMapper.updateById(any(KnowledgeItem.class))).thenReturn(1);

        knowledgeService.updateKnowledge(1L, 1L,
                new KnowledgeUpdateRequest("新标题", "Go", "笔记", "新内容"));

        ArgumentCaptor<KnowledgeItem> captor = ArgumentCaptor.forClass(KnowledgeItem.class);
        verify(knowledgeItemMapper).updateById(captor.capture());
        assertThat(captor.getValue().getContent()).isEqualTo("新内容");
        verify(knowledgeChunkMapper).delete(any(QueryWrapper.class));
        verify(embeddingService, timeout(2000)).rebuildChunksForItem(eq(1L), eq("新内容"));
    }

    @Test
    void updateKnowledge_contentNull_skipsContentUpdateAndRebuild() {
        KnowledgeItem item = createItem(1L, 1L, "Spring Guide");
        item.setContent("旧内容");
        when(knowledgeItemMapper.selectById(1L)).thenReturn(item);
        when(knowledgeItemMapper.updateById(any(KnowledgeItem.class))).thenReturn(1);

        knowledgeService.updateKnowledge(1L, 1L,
                new KnowledgeUpdateRequest("新标题", "Go", "笔记", null));

        ArgumentCaptor<KnowledgeItem> captor = ArgumentCaptor.forClass(KnowledgeItem.class);
        verify(knowledgeItemMapper).updateById(captor.capture());
        assertThat(captor.getValue().getContent()).isEqualTo("旧内容");
        verify(knowledgeChunkMapper, never()).delete(any(QueryWrapper.class));
        verify(embeddingService, never()).rebuildChunksForItem(anyLong(), anyString());
    }

    @Test
    void updateKnowledge_contentUnchanged_skipsRebuild() {
        KnowledgeItem item = createItem(1L, 1L, "Spring Guide");
        item.setContent("相同内容");
        when(knowledgeItemMapper.selectById(1L)).thenReturn(item);
        when(knowledgeItemMapper.updateById(any(KnowledgeItem.class))).thenReturn(1);

        knowledgeService.updateKnowledge(1L, 1L,
                new KnowledgeUpdateRequest("新标题", "Go", "笔记", "相同内容"));

        verify(knowledgeChunkMapper, never()).delete(any(QueryWrapper.class));
        verify(embeddingService, never()).rebuildChunksForItem(anyLong(), anyString());
    }

    @Test
    void updateKnowledge_contentChanged_triggersAsyncRebuildLinks() {
        KnowledgeItem item = createItem(1L, 1L, "Spring Guide");
        item.setContent("旧内容");
        when(knowledgeItemMapper.selectById(1L)).thenReturn(item);
        when(knowledgeItemMapper.updateById(any(KnowledgeItem.class))).thenReturn(1);
        when(knowledgeItemMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of());

        knowledgeService.updateKnowledge(1L, 1L,
                new KnowledgeUpdateRequest("Spring", "Go", "笔记", "新内容"));

        verify(knowledgeItemMapper, timeout(2000)).selectList(any(QueryWrapper.class));
    }

    @Test
    void updateKnowledge_contentTooLong_throwsException() {
        when(knowledgeItemMapper.selectById(1L)).thenReturn(createItem(1L, 1L, "x"));
        String longContent = "a".repeat(200001);
        assertThatThrownBy(() -> knowledgeService.updateKnowledge(1L, 1L,
                new KnowledgeUpdateRequest("标题", "Go", "n", longContent)))
                .hasMessageContaining("请求参数错误");
    }

    @Test
    void aggregateTags_returnsDistinctSorted() {
        KnowledgeItem a = createItem(1L, 1L, "a");
        a.setTags("Go,并发");
        KnowledgeItem b = createItem(2L, 1L, "b");
        b.setTags("并发");
        KnowledgeItem c = createItem(3L, 1L, "c");
        c.setTags("Vue");
        KnowledgeItem d = createItem(4L, 1L, "d");
        when(knowledgeItemMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(a, b, c, d));

        List<String> tags = knowledgeService.aggregateTags(1L);
        assertThat(tags).containsExactly("Go", "Vue", "并发");
    }

    @Test
    void aggregateTags_noTags_returnsEmpty() {
        when(knowledgeItemMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of());
        assertThat(knowledgeService.aggregateTags(1L)).isEmpty();
    }

    // === 跨条目关联（双通道：标题子串 + 向量相似度）===

    private static KnowledgeChunk chunkOf(Long itemId, float[] vec) {
        KnowledgeChunk c = new KnowledgeChunk();
        c.setKnowledgeItemId(itemId);
        c.setChunkIndex(0);
        c.setChunkText("text");
        c.setEmbedding(java.util.Base64.getEncoder().encodeToString(
                com.dart.util.VectorUtils.floatArrayToBytes(vec)));
        return c;
    }

    private static float[] fullVector(float fill) {
        float[] vec = new float[1024];
        java.util.Arrays.fill(vec, fill);
        return vec;
    }

    @Test
    void linkRelatedItems_titleSubstring_createsRelation() {
        when(knowledgeItemMapper.selectById(1L)).thenReturn(createItem(1L, 1L, "Spring Boot"));
        when(knowledgeItemMapper.selectList(any(QueryWrapper.class)))
                .thenReturn(List.of(createItem(2L, 1L, "Spring")));
        when(knowledgeChunkMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of());
        when(relationMapper.selectCount(any())).thenReturn(0L);

        knowledgeService.linkRelatedItems(1L, 1L);

        ArgumentCaptor<KnowledgeRelation> captor = ArgumentCaptor.forClass(KnowledgeRelation.class);
        verify(relationMapper).insert(captor.capture());
        assertThat(captor.getValue().getSubjectId()).isEqualTo(1L);
        assertThat(captor.getValue().getObjectId()).isEqualTo(2L);
        assertThat(captor.getValue().getPredicate()).isEqualTo("related_to");
        assertThat(captor.getValue().getSource()).contains("TITLE_MATCH");
    }

    @Test
    void linkRelatedItems_caseInsensitiveSubstring_matches() {
        when(knowledgeItemMapper.selectById(1L)).thenReturn(createItem(1L, 1L, "spring"));
        when(knowledgeItemMapper.selectList(any(QueryWrapper.class)))
                .thenReturn(List.of(createItem(2L, 1L, "Spring Boot")));
        when(knowledgeChunkMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of());
        when(relationMapper.selectCount(any())).thenReturn(0L);

        knowledgeService.linkRelatedItems(1L, 1L);

        verify(relationMapper).insert(any(KnowledgeRelation.class));
    }

    @Test
    void linkRelatedItems_equalTitles_skips() {
        when(knowledgeItemMapper.selectById(1L)).thenReturn(createItem(1L, 1L, "Spring"));
        when(knowledgeItemMapper.selectList(any(QueryWrapper.class)))
                .thenReturn(List.of(createItem(2L, 1L, "Spring")));
        when(knowledgeChunkMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of());

        knowledgeService.linkRelatedItems(1L, 1L);

        verify(relationMapper, never()).insert(any(KnowledgeRelation.class));
    }

    @Test
    void linkRelatedItems_singleCharSubstring_skips() {
        when(knowledgeItemMapper.selectById(1L)).thenReturn(createItem(1L, 1L, "AOP"));
        when(knowledgeItemMapper.selectList(any(QueryWrapper.class)))
                .thenReturn(List.of(createItem(2L, 1L, "A")));
        when(knowledgeChunkMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of());

        knowledgeService.linkRelatedItems(1L, 1L);

        verify(relationMapper, never()).insert(any(KnowledgeRelation.class));
    }

    @Test
    void linkRelatedItems_sharedTitleKeyword_createsRelation() {
        when(knowledgeItemMapper.selectById(1L))
                .thenReturn(createItem(1L, 1L, "Spring Boot 自动配置原理是什么？"));
        when(knowledgeItemMapper.selectList(any(QueryWrapper.class)))
                .thenReturn(List.of(createItem(2L, 1L, "Spring Cloud 与 Spring Boot 有什么区别？")));
        when(knowledgeChunkMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of());
        when(relationMapper.selectCount(any())).thenReturn(0L);

        knowledgeService.linkRelatedItems(1L, 1L);

        ArgumentCaptor<KnowledgeRelation> captor = ArgumentCaptor.forClass(KnowledgeRelation.class);
        verify(relationMapper).insert(captor.capture());
        assertThat(captor.getValue().getSource()).contains("TITLE_KEYWORD");
        assertThat(captor.getValue().getPredicate()).isEqualTo("related_to");
    }

    @Test
    void linkRelatedItems_noSharedKeyword_skips() {
        when(knowledgeItemMapper.selectById(1L))
                .thenReturn(createItem(1L, 1L, "Spring Boot 自动配置原理是什么？"));
        when(knowledgeItemMapper.selectList(any(QueryWrapper.class)))
                .thenReturn(List.of(createItem(2L, 1L, "Go 语言的 goroutine 和 channel 如何使用？")));
        when(knowledgeChunkMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of());

        knowledgeService.linkRelatedItems(1L, 1L);

        verify(relationMapper, never()).insert(any(KnowledgeRelation.class));
    }

    @Test
    void linkRelatedItems_punctuationDifference_stillSharesKeyword() {
        when(knowledgeItemMapper.selectById(1L))
                .thenReturn(createItem(1L, 1L, "自动配置原理？"));
        when(knowledgeItemMapper.selectList(any(QueryWrapper.class)))
                .thenReturn(List.of(createItem(2L, 1L, "自动配置原理！")));
        when(knowledgeChunkMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of());
        when(relationMapper.selectCount(any())).thenReturn(0L);

        knowledgeService.linkRelatedItems(1L, 1L);

        ArgumentCaptor<KnowledgeRelation> captor = ArgumentCaptor.forClass(KnowledgeRelation.class);
        verify(relationMapper).insert(captor.capture());
        assertThat(captor.getValue().getSource()).contains("TITLE_KEYWORD");
    }

    @Test
    void linkRelatedItems_vectorSimilarity_createsRelation() {
        when(knowledgeItemMapper.selectById(1L)).thenReturn(createItem(1L, 1L, "Kubernetes"));
        when(knowledgeItemMapper.selectList(any(QueryWrapper.class)))
                .thenReturn(List.of(createItem(2L, 1L, "Spring Boot")));
        when(knowledgeChunkMapper.selectList(any(QueryWrapper.class)))
                .thenReturn(List.of(chunkOf(1L, fullVector(0.5f)), chunkOf(2L, fullVector(0.5f))));
        when(relationMapper.selectCount(any())).thenReturn(0L);

        knowledgeService.linkRelatedItems(1L, 1L);

        ArgumentCaptor<KnowledgeRelation> captor = ArgumentCaptor.forClass(KnowledgeRelation.class);
        verify(relationMapper).insert(captor.capture());
        assertThat(captor.getValue().getSource()).contains("VECTOR_SIMILARITY");
    }

    @Test
    void linkRelatedItems_noMatch_skips() {
        when(knowledgeItemMapper.selectById(1L)).thenReturn(createItem(1L, 1L, "Kubernetes"));
        when(knowledgeItemMapper.selectList(any(QueryWrapper.class)))
                .thenReturn(List.of(createItem(2L, 1L, "Spring Boot")));
        when(knowledgeChunkMapper.selectList(any(QueryWrapper.class)))
                .thenReturn(List.of(chunkOf(1L, fullVector(0.0f)), chunkOf(2L, fullVector(0.5f))));

        knowledgeService.linkRelatedItems(1L, 1L);

        verify(relationMapper, never()).insert(any(KnowledgeRelation.class));
    }

    @Test
    void linkRelatedItems_existingRelation_skips() {
        when(knowledgeItemMapper.selectById(1L)).thenReturn(createItem(1L, 1L, "Spring Boot"));
        when(knowledgeItemMapper.selectList(any(QueryWrapper.class)))
                .thenReturn(List.of(createItem(2L, 1L, "Spring")));
        when(knowledgeChunkMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of());
        when(relationMapper.selectCount(any())).thenReturn(1L);

        knowledgeService.linkRelatedItems(1L, 1L);

        verify(relationMapper, never()).insert(any(KnowledgeRelation.class));
    }

    @Test
    void rebuildLinks_createsAllPairs() {
        when(knowledgeItemMapper.selectList(any(QueryWrapper.class)))
                .thenReturn(List.of(
                        createItem(1L, 1L, "Spring"),
                        createItem(2L, 1L, "Spring Boot"),
                        createItem(3L, 1L, "Spring Cloud")));
        when(relationMapper.selectCount(any())).thenReturn(0L);

        int created = knowledgeService.rebuildLinks(1L);

        // (Spring,Spring Boot) 与 (Spring,Spring Cloud) 子串命中；(Spring Boot,Spring Cloud) 无
        assertThat(created).isEqualTo(2);
        verify(relationMapper, times(2)).insert(any(KnowledgeRelation.class));
    }

    @Test
    void rebuildLinks_idempotent_secondRunSkipsExisting() {
        when(knowledgeItemMapper.selectList(any(QueryWrapper.class)))
                .thenReturn(List.of(
                        createItem(1L, 1L, "Spring"),
                        createItem(2L, 1L, "Spring Boot")));
        when(relationMapper.selectCount(any())).thenReturn(0L, 1L);

        assertThat(knowledgeService.rebuildLinks(1L)).isEqualTo(1);
        assertThat(knowledgeService.rebuildLinks(1L)).isZero();
        verify(relationMapper, times(1)).insert(any(KnowledgeRelation.class));
    }

    @Test
    void rebuildLinks_emptyItems_returnsZero() {
        when(knowledgeItemMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of());
        assertThat(knowledgeService.rebuildLinks(1L)).isZero();
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
    void getGlobalGraph_returnsAllItemsAndItemLinks() {
        when(knowledgeItemMapper.selectList(any(QueryWrapper.class)))
                .thenReturn(List.of(
                        createItem(1L, 1L, "Spring"),
                        createItem(2L, 1L, "Spring Boot"),
                        createItem(3L, 1L, "Go")));
        KnowledgeRelation r1 = relation(1L, "related_to", 2L, null);
        KnowledgeRelation r2 = relation(1L, "related_to", 3L, null);
        when(relationMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(r1, r2));

        KnowledgeGraphDTO graph = knowledgeService.getGlobalGraph(1L);

        assertThat(graph.nodes()).extracting(KnowledgeGraphDTO.GraphNode::id)
                .containsExactly("item:1", "item:2", "item:3");
        assertThat(graph.edges()).hasSize(2);
        assertThat(graph.edges()).extracting(KnowledgeGraphDTO.GraphEdge::label)
                .containsExactly("related_to", "related_to");
    }

    @Test
    void getGlobalGraph_excludesExternalConcepts() {
        when(knowledgeItemMapper.selectList(any(QueryWrapper.class)))
                .thenReturn(List.of(createItem(1L, 1L, "Spring Boot")));
        KnowledgeRelation external = relation(1L, "depends_on", null, "JVM");
        when(relationMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(external));

        KnowledgeGraphDTO graph = knowledgeService.getGlobalGraph(1L);

        assertThat(graph.nodes()).extracting(KnowledgeGraphDTO.GraphNode::id)
                .containsExactly("item:1");
        assertThat(graph.nodes()).extracting(KnowledgeGraphDTO.GraphNode::category)
                .doesNotContain("external");
        assertThat(graph.edges()).isEmpty();
    }

    @Test
    void getGlobalGraph_filtersEdgesToUnknownItems() {
        when(knowledgeItemMapper.selectList(any(QueryWrapper.class)))
                .thenReturn(List.of(createItem(1L, 1L, "Spring Boot")));
        KnowledgeRelation toUnknown = relation(1L, "related_to", 99L, null);
        when(relationMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(toUnknown));

        KnowledgeGraphDTO graph = knowledgeService.getGlobalGraph(1L);

        assertThat(graph.nodes()).hasSize(1);
        assertThat(graph.edges()).isEmpty();
    }

    @Test
    void getGlobalGraph_emptyItems_returnsEmptyGraph() {
        when(knowledgeItemMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of());

        KnowledgeGraphDTO graph = knowledgeService.getGlobalGraph(1L);

        assertThat(graph.nodes()).isEmpty();
        assertThat(graph.edges()).isEmpty();
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
