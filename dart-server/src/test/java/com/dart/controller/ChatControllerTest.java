package com.dart.controller;

import com.dart.broadcast.BroadcasterRegistry;
import com.dart.broadcast.SessionBroadcaster;
import com.dart.dto.request.ChatRequest;
import com.dart.dto.response.ConversationVO;
import com.dart.entity.User;
import com.dart.exception.BusinessException;
import com.dart.exception.ErrorCode;
import com.dart.security.UserPrincipal;
import com.dart.service.ChatService;
import com.dart.service.ConversationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@ExtendWith(MockitoExtension.class)
class ChatControllerTest {

    @Mock
    private ChatService chatService;
    @Mock
    private ConversationService conversationService;
    @Mock
    private BroadcasterRegistry broadcasterRegistry;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        User user = new User();
        user.setId(1L);
        user.setUsername("testuser");
        user.setPasswordHash("hash");
        user.setStatus(User.STATUS_ACTIVE);
        UserPrincipal principal = new UserPrincipal(user);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
        mockMvc = MockMvcBuilders.standaloneSetup(
                new ChatController(chatService, conversationService, broadcasterRegistry)).build();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void resume_existingGeneration_subscribesAndStreams() throws Exception {
        SessionBroadcaster broadcaster = new SessionBroadcaster(55L);
        lenient().when(broadcasterRegistry.get(55L)).thenReturn(broadcaster);

        // 生成中会话：SSE 异步流返回，广播器被订阅
        mockMvc.perform(post("/api/chat/resume")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"conversationId\":55}"))
                .andExpect(request().asyncStarted());
        verify(broadcasterRegistry).get(55L);
    }

    @Test
    void send_withForeignConversationId_rejectsWithoutRegisteringBroadcaster() throws Exception {
        ChatRequest request = new ChatRequest();
        request.setConversationId(99L);
        request.setMessage("test");
        when(conversationService.getConversation(1L, 99L))
                .thenThrow(new BusinessException(ErrorCode.FORBIDDEN));

        assertThatThrownBy(() -> new ChatController(chatService, conversationService, broadcasterRegistry)
                .send(request))
                .isInstanceOf(BusinessException.class);
        verify(broadcasterRegistry, never()).register(99L);
    }

    @Test
    void send_withOwnConversationId_passesOwnershipCheck() throws Exception {
        ChatRequest request = new ChatRequest();
        request.setConversationId(55L);
        request.setMessage("test");
        lenient().when(conversationService.getConversation(1L, 55L)).thenReturn(new ConversationVO());
        lenient().when(broadcasterRegistry.register(55L)).thenReturn(new SessionBroadcaster(55L));

        // 归属校验通过：走到广播器注册（SSE 异步路径），不抛异常
        new ChatController(chatService, conversationService, broadcasterRegistry).send(request);
        verify(broadcasterRegistry).register(55L);
    }

    @Test
    void resume_notGenerating_sendsCompleted() throws Exception {
        lenient().when(broadcasterRegistry.get(55L)).thenReturn(null);

        MockHttpServletResponse response = mockMvc.perform(post("/api/chat/resume")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"conversationId\":55}"))
                .andReturn().getResponse();

        // 未生成中的会话：立即返回 done(completed) 完成信号
        assertThat(response.getStatus()).isEqualTo(200);
    }
}
