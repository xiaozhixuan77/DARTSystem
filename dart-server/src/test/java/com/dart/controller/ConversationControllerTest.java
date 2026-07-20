package com.dart.controller;

import com.dart.dto.request.ConversationUpsertRequest;
import com.dart.dto.response.ConversationVO;
import com.dart.dto.response.MessageVO;
import com.dart.entity.User;
import com.dart.exception.BusinessException;
import com.dart.exception.ErrorCode;
import com.dart.exception.GlobalExceptionHandler;
import com.dart.security.UserPrincipal;
import com.dart.service.ConversationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class ConversationControllerTest {

    @Mock private ConversationService conversationService;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private UserPrincipal principal;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new ConversationController(conversationService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        User user = new User();
        user.setId(1L);
        user.setUsername("testuser");
        user.setPasswordHash("hash");
        user.setStatus(User.STATUS_ACTIVE);
        principal = new UserPrincipal(user);

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void list_returnsConversations() throws Exception {
        ConversationVO vo = new ConversationVO(100L, "测试会话", null, 5,
                LocalDateTime.of(2026, 7, 15, 10, 30));
        when(conversationService.listConversations(1L)).thenReturn(List.of(vo));

        mockMvc.perform(get("/api/conversations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data[0].id").value(100))
                .andExpect(jsonPath("$.data[0].title").value("测试会话"))
                .andExpect(jsonPath("$.data[0].messageCount").value(5));
    }

    @Test
    void create_returnsCreatedConversation() throws Exception {
        ConversationVO vo = new ConversationVO(200L, "新对话", null, 0,
                LocalDateTime.of(2026, 7, 20, 10, 0));
        when(conversationService.createConversation(1L)).thenReturn(vo);

        mockMvc.perform(post("/api/conversations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.id").value(200))
                .andExpect(jsonPath("$.data.title").value("新对话"));
    }

    @Test
    void getMessages_returnsMessageList() throws Exception {
        MessageVO msg = new MessageVO(1L, "user", "测试问题", null,
                LocalDateTime.of(2026, 7, 15, 10, 25));
        when(conversationService.getMessages(eq(1L), eq(100L)))
                .thenReturn(List.of(msg));

        mockMvc.perform(get("/api/conversations/100/messages"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data[0].role").value("user"))
                .andExpect(jsonPath("$.data[0].content").value("测试问题"));
    }

    @Test
    void update_returnsSuccess() throws Exception {
        ConversationUpsertRequest req = new ConversationUpsertRequest();
        req.setTitle("新标题");

        mockMvc.perform(put("/api/conversations/100")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    void delete_returnsSuccess() throws Exception {
        mockMvc.perform(delete("/api/conversations/100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    void list_withoutAuthentication_returns401() throws Exception {
        SecurityContextHolder.clearContext();

        mockMvc.perform(get("/api/conversations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    void getMessages_conversationNotFound_returns6001() throws Exception {
        when(conversationService.getMessages(eq(1L), eq(999L)))
                .thenThrow(new BusinessException(ErrorCode.CONVERSATION_NOT_FOUND));

        mockMvc.perform(get("/api/conversations/999/messages"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(6001));
    }
}
