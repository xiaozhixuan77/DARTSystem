package com.dart.controller;

import com.dart.dto.response.KnowledgeDetailVO;
import com.dart.dto.response.KnowledgeItemVO;
import com.dart.dto.response.PageResult;
import com.dart.entity.User;
import com.dart.exception.GlobalExceptionHandler;
import com.dart.security.UserPrincipal;
import com.dart.service.KnowledgeService;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
class KnowledgeControllerTest {

    @Mock
    private KnowledgeService knowledgeService;

    private MockMvc mockMvc;
    private ObjectMapper objectMapper = new ObjectMapper();

    private static KnowledgeItemVO createItem(Long id, String title) {
        KnowledgeItemVO vo = new KnowledgeItemVO();
        vo.setId(id);
        vo.setTitle(title);
        vo.setSourceUrl("https://example.com");
        vo.setScoreT(5);
        vo.setScoreA(4);
        vo.setScoreD(3);
        vo.setScoreM(4);
        vo.setWeightedScore(new BigDecimal("4.00"));
        vo.setCreatedAt(LocalDateTime.now());
        return vo;
    }

    private UserPrincipal principal;

    @BeforeEach
    void setUp() {
        User user = new User();
        user.setId(1L);
        user.setUsername("test");
        user.setPasswordHash("pass");
        user.setStatus(User.STATUS_ACTIVE);
        principal = new UserPrincipal(user);

        mockMvc = MockMvcBuilders
                .standaloneSetup(new KnowledgeController(knowledgeService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, List.of()));
    }

    @Test
    void listKnowledge_returnsList() throws Exception {
        when(knowledgeService.listUserKnowledge(eq(1L), anyInt(), anyInt()))
                .thenReturn(new PageResult<>(List.of(createItem(1L, "Spring Guide")), 1));

        mockMvc.perform(get("/api/knowledge")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records[0].title").value("Spring Guide"));
    }

    @Test
    void getDetail_returnsDetail() throws Exception {
        KnowledgeDetailVO detail = new KnowledgeDetailVO();
        detail.setId(1L);
        detail.setTitle("Spring Guide");
        detail.setContent("Full content");
        when(knowledgeService.getKnowledgeDetail(1L, 1L)).thenReturn(detail);

        mockMvc.perform(get("/api/knowledge/1")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }

    @Test
    void deleteKnowledge_success() throws Exception {
        mockMvc.perform(delete("/api/knowledge/1")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }
}
