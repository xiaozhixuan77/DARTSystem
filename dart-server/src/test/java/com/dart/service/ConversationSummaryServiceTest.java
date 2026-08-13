package com.dart.service;

import com.dart.entity.Conversation;
import com.dart.exception.BusinessException;
import com.dart.exception.ErrorCode;
import com.dart.mapper.ConversationMapper;
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
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConversationSummaryServiceTest {

    @Mock
    private ChatModel chatModel;
    @Mock
    private ConversationMapper conversationMapper;

    private ConversationSummaryService summaryService;

    private static Conversation conversation(Long id, Long userId, String summary) {
        Conversation c = new Conversation();
        c.setId(id);
        c.setUserId(userId);
        c.setSummary(summary);
        return c;
    }

    @BeforeEach
    void setUp() {
        summaryService = new ConversationSummaryService(chatModel, conversationMapper);
        ReflectionTestUtils.setField(summaryService, "memoryEnabled", true);
        ReflectionTestUtils.setField(summaryService, "summaryMaxChars", 400);
        ReflectionTestUtils.setField(summaryService, "summaryReportChars", 4000);
    }

    // === getSummary ===

    @Test
    void getSummary_returnsStoredSummary() {
        when(conversationMapper.selectById(1L)).thenReturn(conversation(1L, 10L, "主题：Spring"));

        assertThat(summaryService.getSummary(10L, 1L)).isEqualTo("主题：Spring");
    }

    @Test
    void getSummary_emptyWhenNull() {
        when(conversationMapper.selectById(1L)).thenReturn(conversation(1L, 10L, null));

        assertThat(summaryService.getSummary(10L, 1L)).isEmpty();
    }

    @Test
    void getSummary_notOwned_throwsForbidden() {
        when(conversationMapper.selectById(1L)).thenReturn(conversation(1L, 10L, "主题：Spring"));

        assertThatThrownBy(() -> summaryService.getSummary(99L, 1L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.FORBIDDEN);
    }

    // === updateSummary ===

    @Test
    void updateSummary_firstTime_buildsPromptWithoutOldSummaryAndPersists() {
        when(conversationMapper.selectById(1L)).thenReturn(conversation(1L, 10L, null));
        String newSummary = "主题：Spring IoC\n已讨论：IoC/DI 概念、Bean 生命周期\n关键结论：三级缓存解决循环依赖";
        when(chatModel.call(any(Prompt.class))).thenReturn(new ChatResponse(
                List.of(new Generation(new AssistantMessage(newSummary)))));

        summaryService.updateSummary(10L, 1L, "Spring IoC 是什么", "# 报告\n\nSpring IoC 内容");

        ArgumentCaptor<Prompt> captor = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel, timeout(3000)).call(captor.capture());
        String promptText = captor.getValue().getInstructions().getFirst().getText();
        // 无旧摘要：prompt 不含"旧摘要"段，含最新 query 与报告
        assertThat(promptText).contains("Spring IoC 是什么");
        assertThat(promptText).contains("# 报告");
        assertThat(promptText).doesNotContain("【旧摘要】");
        // 结构化输出格式要求
        assertThat(promptText).contains("主题");
        assertThat(promptText).contains("已讨论");

        ArgumentCaptor<Conversation> convCaptor = ArgumentCaptor.forClass(Conversation.class);
        verify(conversationMapper, timeout(3000)).updateById(convCaptor.capture());
        assertThat(convCaptor.getValue().getSummary()).isEqualTo(newSummary);
    }

    @Test
    void updateSummary_incremental_includesOldSummaryInPrompt() {
        when(conversationMapper.selectById(1L)).thenReturn(conversation(1L, 10L, "主题：Spring IoC\n已讨论：概念"));
        when(chatModel.call(any(Prompt.class))).thenReturn(new ChatResponse(
                List.of(new Generation(new AssistantMessage("主题：Spring IoC\n已讨论：概念、三级缓存")))));

        summaryService.updateSummary(10L, 1L, "三级缓存怎么运作", "# 报告\n\n三级缓存原理");

        ArgumentCaptor<Prompt> captor = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel, timeout(3000)).call(captor.capture());
        String promptText = captor.getValue().getInstructions().getFirst().getText();
        // 增量压缩：旧摘要进入 prompt
        assertThat(promptText).contains("主题：Spring IoC\n已讨论：概念");
        assertThat(promptText).contains("三级缓存怎么运作");
    }

    @Test
    void updateSummary_reportTruncatedToConfiguredChars() {
        when(conversationMapper.selectById(1L)).thenReturn(conversation(1L, 10L, null));
        when(chatModel.call(any(Prompt.class))).thenReturn(new ChatResponse(
                List.of(new Generation(new AssistantMessage("主题：X")))));

        String longReport = "ABCDEFGHIJ".repeat(500); // 5000 chars > 4000
        summaryService.updateSummary(10L, 1L, "query", longReport);

        ArgumentCaptor<Prompt> captor = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel, timeout(3000)).call(captor.capture());
        String promptText = captor.getValue().getInstructions().getFirst().getText();
        // 报告截断到 4000 字符
        assertThat(promptText).contains("ABCDEFGHIJ".repeat(400));
        assertThat(promptText).doesNotContain("ABCDEFGHIJ".repeat(500));
    }

    @Test
    void updateSummary_llmThrows_keepsOldSummary() {
        when(conversationMapper.selectById(1L)).thenReturn(conversation(1L, 10L, "旧摘要内容"));
        when(chatModel.call(any(Prompt.class))).thenThrow(new RuntimeException("LLM outage"));

        summaryService.updateSummary(10L, 1L, "query", "报告");

        // fail-open：不写回，保留旧摘要
        verify(conversationMapper, after(1500).never()).updateById(any(Conversation.class));
    }

    @Test
    void updateSummary_blankResponse_keepsOldSummary() {
        when(conversationMapper.selectById(1L)).thenReturn(conversation(1L, 10L, "旧摘要内容"));
        when(chatModel.call(any(Prompt.class))).thenReturn(new ChatResponse(
                List.of(new Generation(new AssistantMessage("   \n  ")))));

        summaryService.updateSummary(10L, 1L, "query", "报告");

        verify(conversationMapper, after(1500).never()).updateById(any(Conversation.class));
    }

    @Test
    void updateSummary_notOwned_throwsForbiddenAndSkipsLlm() {
        when(conversationMapper.selectById(1L)).thenReturn(conversation(1L, 10L, null));

        assertThatThrownBy(() -> summaryService.updateSummary(99L, 1L, "query", "报告"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.FORBIDDEN);
        verify(chatModel, never()).call(any(Prompt.class));
    }

    @Test
    void updateSummary_conversationMissing_throwsNotFound() {
        when(conversationMapper.selectById(1L)).thenReturn(null);

        assertThatThrownBy(() -> summaryService.updateSummary(10L, 1L, "query", "报告"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.CONVERSATION_NOT_FOUND);
        verify(chatModel, never()).call(any(Prompt.class));
    }

    @Test
    void updateSummary_disabled_skipsLlmAndPersist() {
        ReflectionTestUtils.setField(summaryService, "memoryEnabled", false);
        when(conversationMapper.selectById(1L)).thenReturn(conversation(1L, 10L, null));

        summaryService.updateSummary(10L, 1L, "query", "报告");

        verify(chatModel, after(1500).never()).call(any(Prompt.class));
        verify(conversationMapper, after(1500).never()).updateById(any(Conversation.class));
    }
}
