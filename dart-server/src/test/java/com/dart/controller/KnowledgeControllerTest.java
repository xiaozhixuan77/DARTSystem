package com.dart.controller;

import com.dart.dto.request.KnowledgeUpdateRequest;
import com.dart.dto.response.KnowledgeDetailVO;
import com.dart.dto.response.KnowledgeGraphDTO;
import com.dart.dto.response.KnowledgeItemVO;
import com.dart.dto.response.PageResult;
import com.dart.entity.User;
import com.dart.exception.BusinessException;
import com.dart.exception.ErrorCode;
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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
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
        when(knowledgeService.listUserKnowledge(eq(1L), anyInt(), anyInt(), any()))
                .thenReturn(new PageResult<>(List.of(createItem(1L, "Spring Guide")), 1));

        mockMvc.perform(get("/api/knowledge")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records[0].title").value("Spring Guide"));
    }

    @Test
    void listKnowledge_withTag_forwardsTag() throws Exception {
        when(knowledgeService.listUserKnowledge(eq(1L), anyInt(), anyInt(), eq("Go")))
                .thenReturn(new PageResult<>(List.of(createItem(1L, "Go Guide")), 1));

        mockMvc.perform(get("/api/knowledge").param("tag", "Go")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records[0].title").value("Go Guide"));
    }

    @Test
    void updateKnowledge_success() throws Exception {
        mockMvc.perform(put("/api/knowledge/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"新标题\",\"tags\":\"Go,并发\",\"notes\":\"笔记\"}"))
                .andExpect(status().isOk());

        verify(knowledgeService).updateKnowledge(eq(1L), eq(1L), any(KnowledgeUpdateRequest.class));
    }

    @Test
    void updateKnowledge_serviceThrowsBadRequest() throws Exception {
        doThrow(new BusinessException(ErrorCode.BAD_REQUEST))
                .when(knowledgeService)
                .updateKnowledge(eq(1L), eq(1L), any(KnowledgeUpdateRequest.class));

        mockMvc.perform(put("/api/knowledge/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"\",\"tags\":\"\",\"notes\":\"\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void getTags_returnsAggregatedTags() throws Exception {
        when(knowledgeService.aggregateTags(1L)).thenReturn(List.of("Go", "Vue"));

        mockMvc.perform(get("/api/knowledge/tags")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0]").value("Go"));
    }

    @Test
    void rebuildLinks_returnsCreatedCount() throws Exception {
        when(knowledgeService.rebuildLinks(1L)).thenReturn(3);

        mockMvc.perform(post("/api/knowledge/rebuild-links")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(3));
        verify(knowledgeService).rebuildLinks(1L);
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

    @Test
    void getGraph_returnsGraphData() throws Exception {
        KnowledgeGraphDTO dto = new KnowledgeGraphDTO(
                List.of(new KnowledgeGraphDTO.GraphNode("item:1", "Spring Boot", "knowledge"),
                        new KnowledgeGraphDTO.GraphNode("ext:JVM", "JVM", "external")),
                List.of(new KnowledgeGraphDTO.GraphEdge("item:1", "ext:JVM", "depends_on")));
        when(knowledgeService.getGraphData(1L, 1L)).thenReturn(dto);

        mockMvc.perform(get("/api/knowledge/1/graph")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.nodes[0].id").value("item:1"))
                .andExpect(jsonPath("$.data.nodes[1].category").value("external"))
                .andExpect(jsonPath("$.data.edges[0].label").value("depends_on"));
    }
}
