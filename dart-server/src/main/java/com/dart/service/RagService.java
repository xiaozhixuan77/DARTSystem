package com.dart.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.dart.entity.KnowledgeChunk;
import com.dart.entity.KnowledgeItem;
import com.dart.mapper.KnowledgeChunkMapper;
import com.dart.mapper.KnowledgeItemMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * RAG 向量检索与回答生成。
 */
@Slf4j
@Service
public class RagService {

    private final EmbeddingService embeddingService;
    private final ChatModel chatModel;
    private final KnowledgeItemMapper knowledgeItemMapper;
    private final KnowledgeChunkMapper knowledgeChunkMapper;
    private final double similarityThreshold;
    private final int topK;

    public RagService(EmbeddingService embeddingService, ChatModel chatModel,
                      KnowledgeItemMapper knowledgeItemMapper,
                      KnowledgeChunkMapper knowledgeChunkMapper,
                      @Value("${agent.rag.similarity-threshold:0.80}") double similarityThreshold,
                      @Value("${agent.rag.top-k:5}") int topK) {
        this.embeddingService = embeddingService;
        this.chatModel = chatModel;
        this.knowledgeItemMapper = knowledgeItemMapper;
        this.knowledgeChunkMapper = knowledgeChunkMapper;
        this.similarityThreshold = similarityThreshold;
        this.topK = topK;
    }

    public record RagResult(String answer, List<String> sourceUrls, double maxSimilarity) {
        public RagResult(String answer, List<String> sourceUrls) {
            this(answer, sourceUrls, -1.0);
        }
    }
    public record RagChunk(String content, String title, String sourceUrl, double similarity) {}

    /**
     * RAG 检索：向量化查询 → 查本地知识库 → 命中则 LLM 生成回答。
     */
    public Optional<RagResult> retrieve(String query, Long userId) {
        try {
            float[] queryVec = embeddingService.embed(query);

            // 先查询用户的知识条目 ID，避免 SQL 注入
            QueryWrapper<KnowledgeItem> itemQuery = new QueryWrapper<>();
            itemQuery.select("id").eq("user_id", userId).eq("is_deleted", 0);
            List<Long> userItemIds = knowledgeItemMapper.selectList(itemQuery)
                    .stream().map(KnowledgeItem::getId).toList();

            if (userItemIds.isEmpty()) {
                return Optional.empty();
            }

            QueryWrapper<KnowledgeChunk> wrapper = new QueryWrapper<>();
            wrapper.in("knowledge_item_id", userItemIds);
            wrapper.last("LIMIT " + topK);
            List<KnowledgeChunk> chunks = knowledgeChunkMapper.selectList(wrapper);

            if (chunks.isEmpty()) {
                return Optional.empty();
            }

            // 批量加载 KnowledgeItem，避免 N+1 查询
            Set<Long> itemIds = new HashSet<>();
            for (KnowledgeChunk c : chunks) {
                if (c.getKnowledgeItemId() != null) {
                    itemIds.add(c.getKnowledgeItemId());
                }
            }
            Map<Long, KnowledgeItem> itemMap = new HashMap<>();
            for (Long id : itemIds) {
                KnowledgeItem item = knowledgeItemMapper.selectById(id);
                if (item != null && userId.equals(item.getUserId()) && item.getIsDeleted() == 0) {
                    itemMap.put(id, item);
                }
            }

            // 用 MySQL 中存储的向量计算余弦相似度（避免重复 Embedding API 调用）
            List<RagChunk> matched = new ArrayList<>();
            for (KnowledgeChunk c : chunks) {
                KnowledgeItem item = itemMap.get(c.getKnowledgeItemId());
                if (item == null || c.getEmbedding() == null) continue;
                float[] chunkVec = EmbeddingService.bytesToFloatArray(
                        java.util.Base64.getDecoder().decode(c.getEmbedding()));
                double similarity = cosineSimilarity(queryVec, chunkVec);
                if (similarity >= similarityThreshold) {
                    matched.add(new RagChunk(c.getChunkText(), item.getTitle(),
                            item.getSourceUrl(), similarity));
                }
            }

            if (matched.isEmpty()) {
                return Optional.empty();
            }

            // 按相似度降序排列
            matched.sort(Comparator.comparingDouble(RagChunk::similarity).reversed());

            String answer = generateAnswer(query, matched);
            List<String> sources = matched.stream().map(RagChunk::sourceUrl).distinct().toList();
            return Optional.of(new RagResult(answer, sources, matched.getFirst().similarity()));

        } catch (Exception e) {
            log.warn("RAG 检索失败: {}", query, e);
            return Optional.empty();
        }
    }

    public String generateAnswer(String query, List<RagChunk> chunks) {
        StringBuilder context = new StringBuilder();
        for (RagChunk c : chunks) {
            context.append("<source>\n");
            context.append("标题: ").append(c.title()).append("\n");
            context.append("来源: ").append(c.sourceUrl()).append("\n");
            context.append("内容: ").append(c.content()).append("\n");
            context.append("</source>\n\n");
        }

        String prompt = """
                你是一个技术问答助手。请基于以下参考材料回答用户问题。
                如果参考材料不足以回答问题，请如实说明。

                用户问题：%s

                参考材料：
                %s

                请生成简洁、准确的中文回答。""".formatted(query.replace("<", "&lt;"), context);

        try {
            return chatModel.call(new Prompt(new UserMessage(prompt)))
                    .getResults().getFirst().getOutput().getText().trim();
        } catch (Exception e) {
            log.warn("LLM 回答生成失败", e);
            return "# " + query + "\n\n基于知识库的搜索结果：\n\n"
                    + chunks.stream()
                    .map(c -> "- [" + c.title() + "](" + c.sourceUrl() + ")")
                    .reduce("", (a, b) -> a + "\n" + b);
        }
    }

    /**
     * 计算两个向量的余弦相似度。
     */
    private static double cosineSimilarity(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length) return 0.0;
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        if (normA == 0 || normB == 0) return 0.0;
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }
}
