package com.dart.config;

import com.dart.agent.curator.CuratorAgent;
import com.dart.agent.curator.CuratorTools;
import com.dart.agent.scoring.MatchScorer;
import com.dart.agent.scoring.QualityScorer;
import com.dart.agent.search.PageFetcher;
import com.dart.agent.search.SearXNGClient;
import com.dart.agent.search.SearchAgent;
import com.dart.agent.search.SearchTools;
import com.dart.agent.splitter.MarkdownSplitter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

/**
 * Agent 层 Bean 注册。
 */
@Configuration
public class AgentConfig {

    @Value("${agent.chunk.size:500}")
    private int chunkSize;

    @Value("${agent.chunk.overlap:50}")
    private int chunkOverlap;

    @Value("${agent.chunk.min-length:20}")
    private int chunkMinLength;

    @Value("${agent.search.max-results:8}")
    private int maxResults;

    @Value("${agent.search.fetch-timeout-ms:10000}")
    private int fetchTimeoutMs;

    @Value("${agent.search.score-threshold:3.0}")
    private double scoreThreshold;

    @Value("${agent.scoring.weights.timeliness:0.15}")
    private double wT;

    @Value("${agent.scoring.weights.authority:0.30}")
    private double wA;

    @Value("${agent.scoring.weights.depth:0.20}")
    private double wD;

    @Value("${agent.scoring.weights.match:0.35}")
    private double wM;

    @Value("${searxng.base-url:http://localhost:8080}")
    private String searxngBaseUrl;

    @Value("${agent.curator.jaccard-threshold:0.85}")
    private double jaccardThreshold;

    // ────────── Splitter ──────────

    @Bean
    public MarkdownSplitter markdownSplitter() {
        return new MarkdownSplitter(chunkSize, chunkOverlap, chunkMinLength);
    }

    // ────────── Scoring ──────────

    @Bean
    public QualityScorer qualityScorer() {
        return new QualityScorer(wT, wA, wD, wM, scoreThreshold);
    }

    @Bean
    public MatchScorer matchScorer(ChatModel chatModel) {
        return new MatchScorer(chatModel);
    }

    // ────────── Search Agent ──────────

    @Bean
    public RestTemplate restTemplate(RestTemplateBuilder builder) {
        return builder
                .connectTimeout(Duration.ofSeconds(5))
                .readTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Bean
    public SearXNGClient searXNGClient(RestTemplate restTemplate, ObjectMapper objectMapper) {
        return new SearXNGClient(restTemplate, searxngBaseUrl, objectMapper);
    }

    @Bean
    public PageFetcher pageFetcher() {
        return new PageFetcher(fetchTimeoutMs);
    }

    @Bean
    public SearchTools searchTools(SearXNGClient searXNGClient, PageFetcher pageFetcher,
                                   QualityScorer qualityScorer, MatchScorer matchScorer) {
        return new SearchTools(searXNGClient, pageFetcher, qualityScorer, matchScorer);
    }

    @Bean
    public SearchAgent searchAgent(SearchTools searchTools) {
        return new SearchAgent(searchTools);
    }

    // ────────── Curator Agent ──────────

    @Bean
    public CuratorTools curatorTools(ChatModel chatModel) {
        return new CuratorTools(chatModel, jaccardThreshold);
    }

    @Bean
    public CuratorAgent curatorAgent(CuratorTools curatorTools) {
        return new CuratorAgent(curatorTools);
    }
}
