package com.dart.service;

import com.dart.entity.KnowledgeEntity;
import com.dart.entity.KnowledgeItemEntity;
import com.dart.mapper.KnowledgeEntityMapper;
import com.dart.mapper.KnowledgeItemEntityMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EntityServiceTest {

    @Mock
    private KnowledgeEntityMapper entityMapper;
    @Mock
    private KnowledgeItemEntityMapper itemEntityMapper;
    @Mock
    private EmbeddingService embeddingService;

    private EntityService service;

    @BeforeEach
    void setUp() {
        service = new EntityService(entityMapper, itemEntityMapper, embeddingService);
    }

    private static KnowledgeEntity entity(Long id, String name, String type) {
        KnowledgeEntity e = new KnowledgeEntity();
        e.setId(id);
        e.setName(name);
        e.setType(type);
        return e;
    }

    @Test
    void upsertEntities_newEntities_insertsWithConceptEmbedding() {
        when(entityMapper.selectList(any())).thenReturn(List.of());
        when(embeddingService.embed("Redis")).thenReturn(new float[]{0.1f, 0.2f});
        // insert 回填 id：topic 先插 98，concept 后插 99
        doAnswer(inv -> {
            KnowledgeEntity e = inv.getArgument(0);
            e.setId(e.getType().equals("topic") ? 98L : 99L);
            return 1;
        }).when(entityMapper).insert(any(com.dart.entity.KnowledgeEntity.class));

        EntityExtractionService.ExtractedEntities extracted =
                new EntityExtractionService.ExtractedEntities(List.of("Spring"), List.of("Redis"));
        List<Long> ids = service.upsertEntities(1L, extracted);

        assertThat(ids).containsExactly(98L, 99L);
        ArgumentCaptor<KnowledgeEntity> captor = ArgumentCaptor.forClass(KnowledgeEntity.class);
        verify(entityMapper, times(2)).insert(captor.capture());
        KnowledgeEntity topic = captor.getAllValues().get(0);
        KnowledgeEntity concept = captor.getAllValues().get(1);
        assertThat(topic.getType()).isEqualTo("topic");
        assertThat(topic.getEmbedding()).isNull();
        assertThat(concept.getType()).isEqualTo("concept");
        assertThat(concept.getEmbedding()).isNotNull();
    }

    @Test
    void upsertEntities_existingName_reusedWithoutInsert() {
        when(entityMapper.selectList(any())).thenReturn(List.of(entity(7L, "Redis", "concept")));

        EntityExtractionService.ExtractedEntities extracted =
                new EntityExtractionService.ExtractedEntities(List.of(), List.of("Redis", "LRU"));
        List<Long> ids = service.upsertEntities(1L, extracted);

        // Redis 复用已有 7；LRU 新插（无 embedding mock 时返回 null 向量 → 不设 embedding）
        assertThat(ids).contains(7L);
        verify(entityMapper, atMostOnce()).insert(any(com.dart.entity.KnowledgeEntity.class));
        verify(embeddingService, atMostOnce()).embed("LRU");
    }

    @Test
    void upsertEntities_caseInsensitiveName_reused() {
        when(entityMapper.selectList(any())).thenReturn(List.of(entity(7L, "Redis", "concept")));

        EntityExtractionService.ExtractedEntities extracted =
                new EntityExtractionService.ExtractedEntities(List.of(), List.of("redis"));
        List<Long> ids = service.upsertEntities(1L, extracted);

        // 大小写不同（Redis/redis）归一化后视为同一实体 → 复用不新建
        assertThat(ids).containsExactly(7L);
        verify(entityMapper, never()).insert(any(com.dart.entity.KnowledgeEntity.class));
    }

    @Test
    void upsertEntities_outerWhitespaceVariant_reused() {
        when(entityMapper.selectList(any())).thenReturn(List.of(entity(7L, "Redis", "concept")));

        EntityExtractionService.ExtractedEntities extracted =
                new EntityExtractionService.ExtractedEntities(List.of(), List.of(" Redis "));
        List<Long> ids = service.upsertEntities(1L, extracted);

        // 首尾空格不同（Redis/ Redis ）归一化后视为同一实体 → 复用不新建
        assertThat(ids).containsExactly(7L);
        verify(entityMapper, never()).insert(any(com.dart.entity.KnowledgeEntity.class));
    }

    @Test
    void upsertEntities_innerWhitespaceVariant_reused() {
        when(entityMapper.selectList(any())).thenReturn(List.of(entity(7L, "LRU 缓存", "concept")));

        EntityExtractionService.ExtractedEntities extracted =
                new EntityExtractionService.ExtractedEntities(List.of(), List.of("LRU缓存"));
        List<Long> ids = service.upsertEntities(1L, extracted);

        // 内部空格不同（LRU 缓存/LRU缓存）归一化后视为同一实体 → 复用不新建
        assertThat(ids).containsExactly(7L);
        verify(entityMapper, never()).insert(any(com.dart.entity.KnowledgeEntity.class));
    }

    @Test
    void linkItems_duplicateAssociation_skipped() {
        when(itemEntityMapper.selectCount(any())).thenReturn(1L);

        service.linkItems(1L, 10L, List.of(5L, 6L));

        verify(itemEntityMapper, never()).insert(any(com.dart.entity.KnowledgeItemEntity.class));
    }

    @Test
    void linkItems_newAssociation_inserted() {
        when(itemEntityMapper.selectCount(any())).thenReturn(0L);

        service.linkItems(1L, 10L, List.of(5L, 6L));

        verify(itemEntityMapper, times(2)).insert(any(com.dart.entity.KnowledgeItemEntity.class));
    }

    @Test
    void listEntities_countsAssociatedItems() {
        when(entityMapper.selectList(any())).thenReturn(List.of(
                entity(1L, "Redis", "concept"), entity(2L, "Spring", "topic")));
        when(itemEntityMapper.selectList(any())).thenReturn(List.of(
                assoc(10L, 1L), assoc(11L, 1L), assoc(12L, 2L)));

        List<com.dart.dto.response.KnowledgeEntityVO> result = service.listEntities(1L, null);

        assertThat(result).hasSize(2);
        assertThat(result).anyMatch(v -> v.name().equals("Redis") && v.itemCount() == 2);
        assertThat(result).anyMatch(v -> v.name().equals("Spring") && v.itemCount() == 1);
    }

    @Test
    void findItemsByEntityName_matchesCaseInsensitiveContains() {
        when(entityMapper.selectList(any())).thenReturn(List.of(
                entity(1L, "Redis", "concept"), entity(2L, "LRU", "concept")));
        when(itemEntityMapper.selectList(any())).thenReturn(List.of(assoc(10L, 1L)));

        // 查询词含 redis（大小写不敏感）→ 只命中 Redis 实体 → 条目 10（LRU 未命中，其关联不被查询）
        assertThat(service.findItemsByEntityName(1L, "redis 缓存淘汰策略")).containsExactly(10L);
    }

    @Test
    void findItemsByEntityName_singleCharName_notMatched() {
        when(entityMapper.selectList(any())).thenReturn(List.of(entity(1L, "V", "concept")));

        // 单字符实体名（"V"）不参与匹配，防 "vue"/"volatile" 等查询误命中
        assertThat(service.findItemsByEntityName(1L, "vue 教程")).isEmpty();
    }

    @Test
    void findItemsByEntityName_twoCharName_notMatched() {
        when(entityMapper.selectList(any())).thenReturn(List.of(entity(1L, "Go", "concept")));
        // lenient：实现过滤后不触达关联查询（旧实现命中时用；防 UnnecessaryStubbing）
        lenient().when(itemEntityMapper.selectList(any())).thenReturn(List.of(assoc(10L, 1L)));

        // 2 字符泛词（网络/网关/其他 等）不再作为检索命中名（区分度不足，引发弱相关免阈值灌入）
        assertThat(service.findItemsByEntityName(1L, "go 语言教程 入门")).isEmpty();
    }

    @Test
    void findItemsByEntityName_fallbackTopic_notMatched() {
        when(entityMapper.selectList(any())).thenReturn(List.of(entity(1L, "其他", "topic")));
        lenient().when(itemEntityMapper.selectList(any())).thenReturn(List.of(assoc(10L, 1L)));

        // 兜底主题"其他"显式排除（无论长度）：泛化路由曾使"C#与其他语言的差别"命中 Java 教程
        assertThat(service.findItemsByEntityName(1L, "C#与其他语言的差别")).isEmpty();
    }

    private static KnowledgeItemEntity assoc(Long itemId, Long entityId) {
        KnowledgeItemEntity a = new KnowledgeItemEntity();
        a.setItemId(itemId);
        a.setEntityId(entityId);
        return a;
    }
}
