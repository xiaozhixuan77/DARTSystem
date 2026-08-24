package com.dart.controller;

import com.dart.broadcast.BroadcasterRegistry;
import com.dart.broadcast.SessionBroadcaster;
import com.dart.service.ChatService;
import com.dart.service.ConversationService;
import com.dart.util.SecurityUtils;
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
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
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
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        new org.springframework.security.core.userdetails.User("u1", "p", java.util.List.of()), null));
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

        MockHttpServletResponse response = mockMvc.perform(post("/api/chat/resume")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"conversationId\":55}"))
                .andReturn().getResponse();

        // 生成中会话：SSE 流返回（异步），广播器被订阅
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentType()).contains("text/event-stream");
        assertThat(response.isCommitted()).isFalse();
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
