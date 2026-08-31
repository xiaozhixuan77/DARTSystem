package com.dart.integration;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.dart.agent.scoring.ScoredResource;
import com.dart.entity.KnowledgeChunk;
import com.dart.entity.KnowledgeItem;
import com.dart.entity.KnowledgeRelation;
import com.dart.entity.User;
import com.dart.mapper.KnowledgeChunkMapper;
import com.dart.mapper.KnowledgeItemMapper;
import com.dart.mapper.KnowledgeRelationMapper;
import com.dart.mapper.UserMapper;
import com.dart.service.EmbeddingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class KnowledgeApiIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private EmbeddingService embeddingService;

    @Autowired
    private KnowledgeItemMapper knowledgeItemMapper;

    @Autowired
    private KnowledgeChunkMapper knowledgeChunkMapper;

    @Autowired
    private KnowledgeRelationMapper knowledgeRelationMapper;

    @Autowired
    private UserMapper userMapper;

    private Long testUserId;

    @BeforeEach
    void setUp() {
        testUserId = createTestUser();
    }

    private Long createItem(String title, String content) {
        KnowledgeItem item = new KnowledgeItem();
        item.setUserId(testUserId);
        item.setTitle(title);
        item.setContent(content);
        item.setSourceUrl("http://example.com/" + System.nanoTime());
        item.setTags("测试");
        item.setIsDeleted(0);
        item.setCreatedAt(LocalDateTime.now());
        item.setUpdatedAt(LocalDateTime.now());
        knowledgeItemMapper.insert(item);
        return item.getId();
    }

    private void addChunk(Long itemId, String text) {
        KnowledgeChunk kc = new KnowledgeChunk();
        kc.setKnowledgeItemId(itemId);
        kc.setChunkIndex(0);
        kc.setChunkText(text);
        kc.setEmbeddingId("chunk:" + itemId + ":0");
        kc.setCreatedAt(LocalDateTime.now());
        knowledgeChunkMapper.insert(kc);
    }

    private void addRelation(Long subjectId, Long objectId) {
        KnowledgeRelation r = new KnowledgeRelation();
        r.setSubjectId(subjectId);
        r.setPredicate("related_to");
        r.setObjectId(objectId);
        r.setConfidence(1.0);
        r.setSource("TITLE_MATCH@item_id=" + subjectId);
        r.setCreatedAt(LocalDateTime.now());
        knowledgeRelationMapper.insert(r);
    }

    // ────────── keyword 全文搜索 ──────────

    @Test
    @DisplayName("keyword 命中标题")
    void keywordMatchesTitle() throws Exception {
        createItem("Spring Boot 指南", "正文内容");
        createItem("Go 语言教程", "其他内容");

        mockMvc.perform(get("/api/knowledge").param("keyword", "Spring")
                        .header("X-User-Id", String.valueOf(testUserId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records[0].title").value("Spring Boot 指南"));
    }

    @Test
    @DisplayName("keyword 命中正文 chunk")
    void keywordMatchesChunkText() throws Exception {
        Long itemId = createItem("麒麟文档", "无关标题内容");
        addChunk(itemId, "这里提到麒麟处理器架构详解");

        mockMvc.perform(get("/api/knowledge").param("keyword", "麒麟处理器")
                        .header("X-User-Id", String.valueOf(testUserId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records[0].title").value("麒麟文档"));
    }

    @Test
    @DisplayName("keyword 不命中返回空")
    void keywordNoMatch() throws Exception {
        createItem("Spring Boot 指南", "正文内容");

        mockMvc.perform(get("/api/knowledge").param("keyword", "完全不存在的词")
                        .header("X-User-Id", String.valueOf(testUserId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0))
                .andExpect(jsonPath("$.data.records.length()").value(0));
    }

    @Test
    @DisplayName("keyword + tag 组合过滤")
    void keywordWithTagCombines() throws Exception {
        Long a = createItem("Spring Boot 指南", "正文");
        Long b = createItem("Spring Cloud 指南", "正文");
        KnowledgeItem itemA = knowledgeItemMapper.selectById(a);
        itemA.setTags("后端");
        knowledgeItemMapper.updateById(itemA);
        KnowledgeItem itemB = knowledgeItemMapper.selectById(b);
        itemB.setTags("微服务");
        knowledgeItemMapper.updateById(itemB);

        mockMvc.perform(get("/api/knowledge").param("keyword", "Spring").param("tag", "后端")
                        .header("X-User-Id", String.valueOf(testUserId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records[0].title").value("Spring Boot 指南"));
    }

    @Test
    @DisplayName("分页 COUNT 与 keyword 一致：命中 2 条 size=2 → total=2")
    void paginationCountConsistentWithKeyword() throws Exception {
        createItem("Spring 入门", "x");
        createItem("Spring Boot 进阶", "x");
        createItem("Go 语言", "x");
        createItem("Vue 入门", "x");
        createItem("React 入门", "x");

        mockMvc.perform(get("/api/knowledge").param("keyword", "Spring").param("size", "2")
                        .header("X-User-Id", String.valueOf(testUserId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.records.length()").value(2));
    }

    // ────────── 正文编辑一致性 ──────────

    @Test
    @DisplayName("PUT content 后详情返回新正文且旧 chunk 被替换")
    void updateContentRebuildsChunks() throws Exception {
        mockEmbedding();
        ScoredResource scores = new ScoredResource("http://example.com/edit", "Edit",
                "旧正文内容", 4, 4, 4, 4, 4.0);
        List<Long> ids = embeddingService.embedAndPersist(testUserId, "可编辑条目",
                "旧正文内容".repeat(20), "http://example.com/edit", scores, "操作指南");
        Long itemId = ids.get(0);
        String newContent = "新正文内容".repeat(20);

        mockMvc.perform(put("/api/knowledge/" + itemId)
                        .header("X-User-Id", String.valueOf(testUserId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"可编辑条目\",\"tags\":\"测试\",\"notes\":\"\","
                                + "\"content\":\"" + newContent + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        mockMvc.perform(get("/api/knowledge/" + itemId)
                        .header("X-User-Id", String.valueOf(testUserId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content").value(newContent));

        // 旧 chunk 已删（同步），新 chunk 异步重建后 chunk_text 不含旧词
        waitForChunkCount(itemId, 1);
        List<KnowledgeChunk> chunks = knowledgeChunkMapper.selectList(
                new QueryWrapper<KnowledgeChunk>().eq("knowledge_item_id", itemId));
        assertThat(chunks).isNotEmpty();
        assertThat(chunks.get(0).getChunkText()).doesNotContain("旧正文内容");
    }

    // ────────── 全局图谱与路由 ──────────

    @Test
    @DisplayName("GET /graph 不被 /{id} 捕获，返回全局节点")
    void globalGraphRouteNotCapturedById() throws Exception {
        createItem("Spring", "x");
        createItem("Spring Boot", "x");

        mockMvc.perform(get("/api/knowledge/graph")
                        .header("X-User-Id", String.valueOf(testUserId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.nodes.length()").value(2));
    }

    @Test
    @DisplayName("全局图谱含条目间边，删除条目后节点消失")
    void globalGraphNodesAndEdgesAfterDelete() throws Exception {
        Long a = createItem("Spring", "x");
        Long b = createItem("Spring Boot", "x");
        addRelation(a, b);

        mockMvc.perform(get("/api/knowledge/graph")
                        .header("X-User-Id", String.valueOf(testUserId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.nodes.length()").value(2))
                .andExpect(jsonPath("$.data.edges.length()").value(1))
                .andExpect(jsonPath("$.data.edges[0].label").value("related_to"));

        org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder delete =
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/knowledge/" + b);
        mockMvc.perform(delete.header("X-User-Id", String.valueOf(testUserId)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/knowledge/graph")
                        .header("X-User-Id", String.valueOf(testUserId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.nodes.length()").value(1))
                .andExpect(jsonPath("$.data.edges.length()").value(0));
    }

    private void waitForChunkCount(Long itemId, int minCount) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            Long count = knowledgeChunkMapper.selectCount(
                    new QueryWrapper<KnowledgeChunk>().eq("knowledge_item_id", itemId));
            if (count >= minCount) {
                return;
            }
            Thread.sleep(100);
        }
    }
}
