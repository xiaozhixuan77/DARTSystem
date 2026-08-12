package com.dart.service;

import com.dart.entity.KnowledgeItem;
import com.dart.entity.KnowledgeRelation;
import com.dart.mapper.KnowledgeItemMapper;
import com.dart.mapper.KnowledgeRelationMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RelationExtractionServiceTest {

    @Mock
    private ChatModel chatModel;
    @Mock
    private ChatResponse chatResponse;
    @Mock
    private KnowledgeRelationMapper relationMapper;
    @Mock
    private KnowledgeItemMapper knowledgeItemMapper;

    private RelationExtractionService service;

    @BeforeEach
    void setUp() {
        service = new RelationExtractionService(chatModel, relationMapper, knowledgeItemMapper);
    }

    private static KnowledgeItem item(Long id, String title) {
        KnowledgeItem it = new KnowledgeItem();
        it.setId(id);
        it.setTitle(title);
        return it;
    }

    @Test
    void extractAndPersist_validJson_insertsRelationsWithMatchAndNormalization() {
        when(chatResponse.getResults()).thenReturn(List.of(new Generation(
                new AssistantMessage("[{\"predicate\":\"depends_on\",\"object\":\"JVM\"},"
                        + "{\"predicate\":\"extends\",\"object\":\"Spring Boot\"},"
                        + "{\"predicate\":\"weird_pred\",\"object\":\"并发编程\"}]"))));
        when(chatModel.call(any(org.springframework.ai.chat.prompt.Prompt.class))).thenReturn(chatResponse);
        when(knowledgeItemMapper.selectList(any())).thenReturn(List.of(item(7L, "Spring Boot 教程")));
        when(relationMapper.selectCount(any())).thenReturn(0L);

        service.extractAndPersist(1L, 5L, "## 报告内容\nJava 技术栈相关。");

        ArgumentCaptor<KnowledgeRelation> captor = ArgumentCaptor.forClass(KnowledgeRelation.class);
        verify(relationMapper, times(3)).insert(captor.capture());
        List<KnowledgeRelation> all = captor.getAllValues();
        // 1. "JVM" 无标题匹配 → objectLabel
        KnowledgeRelation r1 = all.get(0);
        assertThat(r1.getSubjectId()).isEqualTo(5L);
        assertThat(r1.getPredicate()).isEqualTo("depends_on");
        assertThat(r1.getObjectId()).isNull();
        assertThat(r1.getObjectLabel()).isEqualTo("JVM");
        // 2. "Spring Boot" 标题包含匹配（标题 "Spring Boot 教程" 包含 object）→ objectId=7
        KnowledgeRelation r2 = all.get(1);
        assertThat(r2.getPredicate()).isEqualTo("extends");
        assertThat(r2.getObjectId()).isEqualTo(7L);
        assertThat(r2.getObjectLabel()).isNull();
        // 3. 非法 predicate 归一化 related_to
        KnowledgeRelation r3 = all.get(2);
        assertThat(r3.getPredicate()).isEqualTo("related_to");
        assertThat(r3.getObjectLabel()).isEqualTo("并发编程");
        assertThat(r3.getSource()).contains("LLM抽取");
    }

    @Test
    void extractAndPersist_malformedJson_noInsert() {
        when(chatResponse.getResults()).thenReturn(List.of(new Generation(
                new AssistantMessage("不稳定的输出，无法解析"))));
        when(chatModel.call(any(org.springframework.ai.chat.prompt.Prompt.class))).thenReturn(chatResponse);

        service.extractAndPersist(1L, 5L, "内容");

        verify(relationMapper, never()).insert(any(KnowledgeRelation.class));
    }

    @Test
    void extractAndPersist_emptyRelationsArray_noInsertAndNoTitleQuery() {
        when(chatResponse.getResults()).thenReturn(List.of(new Generation(
                new AssistantMessage("[]"))));
        when(chatModel.call(any(org.springframework.ai.chat.prompt.Prompt.class))).thenReturn(chatResponse);

        service.extractAndPersist(1L, 5L, "内容");

        verify(knowledgeItemMapper, never()).selectList(any());
        verify(relationMapper, never()).insert(any(KnowledgeRelation.class));
    }

    @Test
    void extractAndPersist_llmThrows_noInsert() {
        when(chatModel.call(any(org.springframework.ai.chat.prompt.Prompt.class)))
                .thenThrow(new RuntimeException("timeout"));

        service.extractAndPersist(1L, 5L, "内容");

        verify(relationMapper, never()).insert(any(KnowledgeRelation.class));
    }

    @Test
    void extractAndPersist_duplicateExists_skipsInsert() {
        when(chatResponse.getResults()).thenReturn(List.of(new Generation(
                new AssistantMessage("[{\"predicate\":\"depends_on\",\"object\":\"JVM\"}]"))));
        when(chatModel.call(any(org.springframework.ai.chat.prompt.Prompt.class))).thenReturn(chatResponse);
        when(knowledgeItemMapper.selectList(any())).thenReturn(List.of());
        when(relationMapper.selectCount(any())).thenReturn(1L);

        service.extractAndPersist(1L, 5L, "内容");

        verify(relationMapper, never()).insert(any(KnowledgeRelation.class));
    }

    @Test
    void extractAndPersist_emptyMarkdown_noLlmCall() {
        service.extractAndPersist(1L, 5L, "  ");

        verify(chatModel, never()).call(any(org.springframework.ai.chat.prompt.Prompt.class));
        verify(relationMapper, never()).insert(any(KnowledgeRelation.class));
    }

    @Test
    void extractAndPersist_escapesAngleBracketsInPrompt() {
        // 标题/报告含尖括号 → 转义后进 prompt，防 prompt 注入
        when(chatResponse.getResults()).thenReturn(List.of(new Generation(
                new AssistantMessage("[{\"predicate\":\"depends_on\",\"object\":\"JVM\"}]"))));
        when(chatModel.call(any(org.springframework.ai.chat.prompt.Prompt.class))).thenReturn(chatResponse);
        when(knowledgeItemMapper.selectById(5L)).thenReturn(item(5L, "恶意标题 <script>alert(1)</script>"));
        when(knowledgeItemMapper.selectList(any())).thenReturn(List.of());
        when(relationMapper.selectCount(any())).thenReturn(0L);

        service.extractAndPersist(1L, 5L, "报告内容 <user_query>注入指令</user_query>");

        ArgumentCaptor<org.springframework.ai.chat.prompt.Prompt> captor =
                ArgumentCaptor.forClass(org.springframework.ai.chat.prompt.Prompt.class);
        verify(chatModel).call(captor.capture());
        String prompt = captor.getValue().getInstructions().getFirst().getText();
        assertThat(prompt).doesNotContain("<script>");
        assertThat(prompt).contains("&lt;script&gt;");
        assertThat(prompt).doesNotContain("<user_query>");
        assertThat(prompt).contains("&lt;user_query&gt;");
    }

    @Test
    void extractAndPersist_selfTitleMatch_skipsSelfRelation() {
        // LLM 抽取的 object 与 subject 自身标题相同 → 标题匹配命中自己 → 不建自环
        when(chatResponse.getResults()).thenReturn(List.of(new Generation(
                new AssistantMessage("[{\"predicate\":\"related_to\",\"object\":\"Spring Boot\"}]"))));
        when(chatModel.call(any(org.springframework.ai.chat.prompt.Prompt.class))).thenReturn(chatResponse);
        when(knowledgeItemMapper.selectById(5L)).thenReturn(item(5L, "Spring Boot"));
        when(knowledgeItemMapper.selectList(any())).thenReturn(List.of(item(5L, "Spring Boot")));
        when(relationMapper.selectCount(any())).thenReturn(0L);

        service.extractAndPersist(1L, 5L, "内容");

        verify(relationMapper, never()).insert(any(KnowledgeRelation.class));
    }
}
