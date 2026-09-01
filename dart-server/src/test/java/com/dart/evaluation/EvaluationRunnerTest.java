package com.dart.evaluation;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.dart.broadcast.SseSink;
import com.dart.agent.curator.CuratorAgent;
import com.dart.agent.curator.CuratedReport;
import com.dart.agent.scoring.ScoredResource;
import com.dart.agent.search.SearchAgent;
import com.dart.entity.Conversation;
import com.dart.entity.User;
import com.dart.evaluation.model.EvalResult;
import com.dart.evaluation.model.EvalSummary;
import com.dart.evaluation.model.TestQuery;
import com.dart.mapper.ConversationMapper;
import com.dart.mapper.UserMapper;
import com.dart.service.ChatService;
import com.dart.service.RagService;
import com.dart.util.RedisKeyConstants;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import static org.mockito.Mockito.mock;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 评估运行器（真库+真 LLM）：
 *   RUN_EVALUATION=true mvn test -Dtest=EvaluationRunnerTest
 * 可选 EVAL_LIMIT=5 限制条数做局部验证。默认禁跑（环境变量开关），不影响 CI。
 * 数据隔离：专用评估用户 + 每次启动重建 dart_test 表 + 跑前清缓存与旧评估数据。
 */
@Tag("evaluation")
// RANDOM_PORT：保留 servlet web 类型以激活 springdoc/Knife4j（NONE 时 Knife4j 激活但 SpringDocConfigProperties 缺失）
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("eval")
@EnabledIfEnvironmentVariable(named = "RUN_EVALUATION", matches = "true")
class EvaluationRunnerTest {

    private static final long EVAL_USER_ID = 9999999L;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Autowired
    private ChatService chatService;
    @Autowired
    private RagService ragService;
    @Autowired
    private SearchAgent searchAgent;
    @Autowired
    private CuratorAgent curatorAgent;
    @Autowired
    private ChatModel chatModel;
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private ConversationMapper conversationMapper;
    @Autowired
    private StringRedisTemplate redisTemplate;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void runEvaluation() throws Exception {
        prepare();
        List<TestQuery> queries = loadQueries();
        int limit = Integer.parseInt(System.getenv().getOrDefault(
                "EVAL_LIMIT", String.valueOf(queries.size())));
        queries = queries.subList(0, Math.min(limit, queries.size()));

        LlmJudge llmJudge = new LlmJudge(chatModel);
        List<EvalResult> ragResults = new ArrayList<>();
        List<EvalResult> searchResults = new ArrayList<>();
        List<EvalResult> dartResults = new ArrayList<>();
        for (TestQuery q : queries) {
            System.out.println(">>> 评估 " + q.id() + " [" + q.domain() + "] " + q.query());
            ragResults.add(evalRagOnly(q, llmJudge));
            searchResults.add(evalSearchOnly(q, llmJudge));
            dartResults.add(evalDartFull(q, llmJudge));
        }

        Map<String, EvalSummary> byMode = new LinkedHashMap<>();
        byMode.put("RAG-Only", MetricsCalculator.calculate("RAG-Only", ragResults));
        byMode.put("Search-Only", MetricsCalculator.calculate("Search-Only", searchResults));
        byMode.put("DART-Full", MetricsCalculator.calculate("DART-Full", dartResults));

        String runId = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        String markdown = ReportGenerator.generate(runId, byMode);
        Path out = Path.of("target/evaluation/report-" + runId + ".md");
        Files.createDirectories(out.getParent());
        Files.writeString(out, markdown);
        System.out.println("\n评估完成，报告: " + out.toAbsolutePath() + "\n");
        System.out.println(markdown);
        assertTrue(out.toFile().length() > 0, "评估报告应生成");
    }

    private EvalResult evalRagOnly(TestQuery q, LlmJudge llmJudge) {
        long start = System.nanoTime();
        String answer;
        boolean hit;
        try {
            Optional<RagService.RagResult> result = ragService.retrieve(q.query(), q.query(), EVAL_USER_ID);
            hit = result.isPresent();
            answer = result.map(RagService.RagResult::answer).orElse("（RAG 未命中）");
        } catch (Exception e) {
            hit = false;
            answer = "（RAG 异常: " + e.getMessage() + "）";
        }
        long latency = (System.nanoTime() - start) / 1_000_000;
        return new EvalResult(q.id(), q.domain(), "RAG-Only",
                llmJudge.score(q.query(), answer, q.keyConcepts()), latency, hit);
    }

    private EvalResult evalSearchOnly(TestQuery q, LlmJudge llmJudge) {
        long start = System.nanoTime();
        String answer;
        try {
            List<ScoredResource> results = searchAgent.execute(List.of(q.query()), 8, mock(SseSink.class));
            if (results.isEmpty()) {
                answer = "（无搜索结果）";
            } else {
                CuratedReport report = curatorAgent.curate(q.query(), results, mock(SseSink.class),
                        null, null, false, null);
                answer = report.markdown();
            }
        } catch (Exception e) {
            answer = "（Search 异常: " + e.getMessage() + "）";
        }
        long latency = (System.nanoTime() - start) / 1_000_000;
        return new EvalResult(q.id(), q.domain(), "Search-Only",
                llmJudge.score(q.query(), answer, q.keyConcepts()), latency, false);
    }

    private EvalResult evalDartFull(TestQuery q, LlmJudge llmJudge) {
        Conversation conv = new Conversation();
        conv.setUserId(EVAL_USER_ID);
        conv.setTitle(q.id() + " " + q.query());
        conv.setIsDeleted(0);
        conv.setCreatedAt(LocalDateTime.now());
        conv.setUpdatedAt(LocalDateTime.now());
        conversationMapper.insert(conv);

        long start = System.nanoTime();
        String answer;
        boolean ragHit;
        try {
            ChatService.SearchResult result = chatService.executeSearchAndCurate(
                    q.query(), EVAL_USER_ID, conv.getId(), mock(SseSink.class));
            ragHit = result.isRagHit();
            answer = ragHit ? result.ragAnswer().answer() : result.curatedReport().markdown();
        } catch (Exception e) {
            ragHit = false;
            answer = "（DART-Full 异常: " + e.getMessage() + "）";
        }
        long latency = (System.nanoTime() - start) / 1_000_000;
        return new EvalResult(q.id(), q.domain(), "DART-Full",
                llmJudge.score(q.query(), answer, q.keyConcepts()), latency, ragHit);
    }

    private void prepare() {
        User user = userMapper.selectOne(new QueryWrapper<User>().eq("username", "eval_runner"));
        if (user == null) {
            user = new User();
            user.setId(EVAL_USER_ID);
            user.setUsername("eval_runner");
            user.setPasswordHash("$2a$10$evaluation-placeholder-hash");
            user.setStatus(0);
            user.setCreatedAt(LocalDateTime.now());
            userMapper.insert(user);
        }
        // 清理旧评估数据（外键顺序：子表先删）
        jdbcTemplate.update("DELETE FROM search_trace WHERE user_id = ?", EVAL_USER_ID);
        jdbcTemplate.update("DELETE FROM message WHERE conversation_id IN (SELECT id FROM conversation WHERE user_id = ?)", EVAL_USER_ID);
        jdbcTemplate.update("DELETE FROM knowledge_relation WHERE subject_id IN (SELECT id FROM knowledge_item WHERE user_id = ?)", EVAL_USER_ID);
        jdbcTemplate.update("DELETE FROM knowledge_chunk WHERE knowledge_item_id IN (SELECT id FROM knowledge_item WHERE user_id = ?)", EVAL_USER_ID);
        jdbcTemplate.update("DELETE FROM conversation WHERE user_id = ?", EVAL_USER_ID);
        jdbcTemplate.update("DELETE FROM knowledge_item WHERE user_id = ?", EVAL_USER_ID);
        // 清报告缓存（防缓存命中污染评估）
        Set<String> keys = redisTemplate.keys(RedisKeyConstants.REPORT_CACHE_PREFIX + "*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }

    private List<TestQuery> loadQueries() throws Exception {
        byte[] bytes = getClass().getResourceAsStream("/evaluation/evaluation-queries.json")
                .readAllBytes();
        return List.of(OBJECT_MAPPER.readValue(bytes, TestQuery[].class));
    }
}
