package com.dart.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.dart.entity.KnowledgeChunk;
import com.dart.entity.KnowledgeItem;
import com.dart.mapper.KnowledgeChunkMapper;
import com.dart.mapper.KnowledgeItemMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;

import java.util.List;
import java.util.Optional;

/**
 * RAG 向量检索与回答生成。
 */
public class RagService {

    private static final Logger log = LoggerFactory.getLogger(RagService.class);

    private final EmbeddingModel embeddingModel;
    private final ChatModel chatModel;
    private final KnowledgeItemMapper knowledgeItemMapper;
    private final KnowledgeChunkMapper knowledgeChunkMapper;
    private final double similarityThreshold;
    private final int topK;

    public RagService(EmbeddingModel embeddingModel, ChatModel chatModel,
                      KnowledgeItemMapper knowledgeItemMapper,
                      KnowledgeChunkMapper knowledgeChunkMapper,
                      double similarityThreshold, int topK) {
        this.embeddingModel = embeddingModel;
        this.chatModel = chatModel;
        this.knowledgeItemMapper = knowledgeItemMapper;
        this.knowledgeChunkMapper = knowledgeChunkMapper;
        this.similarityThreshold = similarityThreshold;
        this.topK = topK;
    }

    public record RagResult(String answer, List<String> sourceUrls) {}
    public record RagChunk(String content, String title, String sourceUrl, double similarity) {}

    /**
     * RAG 检索：向量化查询 → 查本地知识库 → 命中则 LLM 生成回答。
     */
    public Optional<RagResult> retrieve(String query, Long userId) {
        try {
            float[] queryVec = embed(query);
            // 简化：通过 userId 查询知识库条目，按前缀匹配
            QueryWrapper<KnowledgeChunk> wrapper = new QueryWrapper<>();
            wrapper.last("LIMIT " + topK);
            List<KnowledgeChunk> chunks = knowledgeChunkMapper.selectList(wrapper);

            if (chunks.isEmpty()) {
                return Optional.empty();
            }

            // 过滤属于当前用户的 chunk
            List<RagChunk> validChunks = chunks.stream()
                    .filter(c -> {
                        KnowledgeItem item = knowledgeItemMapper.selectById(c.getKnowledgeItemId());
                        return item != null && userId.equals(item.getUserId()) && item.getIsDeleted() == 0;
                    })
                    .map(c -> {
                        KnowledgeItem item = knowledgeItemMapper.selectById(c.getKnowledgeItemId());
                        return new RagChunk(c.getChunkText(), item.getTitle(), item.getSourceUrl(), 0.85);
                    })
                    .toList();

            if (validChunks.isEmpty()) {
                return Optional.empty();
            }

            // 只保留相似度 >= threshold 的（简化处理，后续接入 Redis KNN）
            List<RagChunk> matched = validChunks.stream()
                    .filter(c -> c.similarity() >= similarityThreshold)
                    .toList();

            if (matched.isEmpty()) {
                return Optional.empty();
            }

            String answer = generateAnswer(query, matched);
            List<String> sources = matched.stream().map(RagChunk::sourceUrl).distinct().toList();
            return Optional.of(new RagResult(answer, sources));

        } catch (Exception e) {
            log.warn("RAG 检索失败: {}", query, e);
            return Optional.empty();
        }
    }

    public String generateAnswer(String query, List<RagChunk> chunks) {
        StringBuilder context = new StringBuilder();
        for (RagChunk c : chunks) {
            context.append("标题: ").append(c.title()).append("\n");
            context.append("来源: ").append(c.sourceUrl()).append("\n");
            context.append("内容: ").append(c.content()).append("\n\n");
        }

        String prompt = """
                你是一个技术问答助手。请基于以下参考材料回答用户问题。
                如果参考材料不足以回答问题，请如实说明。

                用户问题：%s

                参考材料：
                %s

                请生成简洁、准确的中文回答。""".formatted(query, context);

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

    private float[] embed(String text) {
        var response = embeddingModel.call(new EmbeddingRequest(
                List.of(text),
                org.springframework.ai.embedding.DefaultEmbeddingOptions.builder().build()));
        return response.getResult().getOutput();
    }
}
