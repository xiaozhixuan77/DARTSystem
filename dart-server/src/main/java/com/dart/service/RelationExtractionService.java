package com.dart.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.dart.entity.KnowledgeItem;
import com.dart.entity.KnowledgeRelation;
import com.dart.mapper.KnowledgeItemMapper;
import com.dart.mapper.KnowledgeRelationMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 知识关系抽取：报告沉淀后单次 LLM 调用抽三元组（subject 固定为当前条目），
 * object 与知识库标题做包含匹配，未命中存外部概念标签；失败仅告警不落库。
 */
@Slf4j
@Service
public class RelationExtractionService {

    private static final Set<String> PREDICATES = Set.of(
            "related_to", "prerequisite_of", "extends", "part_of", "alternative_to", "depends_on");

    private record ExtractedRelation(String predicate, String object) {}

    private final ChatModel chatModel;
    private final KnowledgeRelationMapper relationMapper;
    private final KnowledgeItemMapper knowledgeItemMapper;

    public RelationExtractionService(ChatModel chatModel,
                                     KnowledgeRelationMapper relationMapper,
                                     KnowledgeItemMapper knowledgeItemMapper) {
        this.chatModel = chatModel;
        this.relationMapper = relationMapper;
        this.knowledgeItemMapper = knowledgeItemMapper;
    }

    /**
     * 抽取并落库关系；LLM 失败/解析失败/全重复时静默跳过，不影响主流程。
     */
    @Transactional
    public void extractAndPersist(Long userId, Long itemId, String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return;
        }
        List<ExtractedRelation> relations;
        try {
            KnowledgeItem subject = knowledgeItemMapper.selectById(itemId);
            String title = subject == null || subject.getTitle() == null ? "" : subject.getTitle();
            String sanitized = markdown.length() > 6000 ? markdown.substring(0, 6000) : markdown;
            // 标题/报告可含用户输入，转义尖括号防 prompt 注入（与 preProcessQuery/regenerateReport 一致）
            String prompt = """
                    从以下学习报告中抽取 3-6 条核心概念关系。subject 固定为报告主题「%s」。
                    只输出 JSON 数组，不要输出其他内容：
                    [{"predicate": "关系类型", "object": "概念名"}]

                    关系类型取值（严格使用以下六种）：
                    related_to: 一般相关 | prerequisite_of: 前置知识 | extends: 扩展/继承
                    part_of: 组成部分 | alternative_to: 替代方案 | depends_on: 技术依赖

                    object 是报告中的关键技术概念/术语/工具名，不要输出长句。

                    <report>
                    %s
                    </report>
                    """.formatted(title.replace("<", "&lt;").replace(">", "&gt;"),
                    sanitized.replace("<", "&lt;").replace(">", "&gt;"));
            String response = chatModel.call(new Prompt(new UserMessage(prompt)))
                    .getResults().getFirst().getOutput().getText();
            relations = parseRelations(response);
        } catch (Exception e) {
            log.warn("关系抽取 LLM 调用失败，跳过: itemId={}", itemId, e);
            return;
        }
        if (relations.isEmpty()) {
            return;
        }

        Map<String, Long> titleIndex = loadTitleIndex(userId);
        for (ExtractedRelation relation : relations) {
            String predicate = PREDICATES.contains(relation.predicate()) ? relation.predicate() : "related_to";
            Long objectId = matchObjectId(relation.object(), titleIndex);
            String objectLabel = objectId == null ? relation.object() : null;
            if (exists(itemId, predicate, objectId, objectLabel)) {
                log.debug("关系已存在，跳过: itemId={}, predicate={}, object={}",
                        itemId, predicate, relation.object());
                continue;
            }
            KnowledgeRelation entity = new KnowledgeRelation();
            entity.setSubjectId(itemId);
            entity.setPredicate(predicate);
            entity.setObjectId(objectId);
            entity.setObjectLabel(objectLabel);
            entity.setConfidence(1.0);
            entity.setSource("LLM抽取@item_id=" + itemId);
            entity.setCreatedAt(LocalDateTime.now());
            relationMapper.insert(entity);
        }
    }

    private Map<String, Long> loadTitleIndex(Long userId) {
        QueryWrapper<KnowledgeItem> wrapper = new QueryWrapper<>();
        wrapper.select("id", "title").eq("user_id", userId).eq("is_deleted", 0);
        Map<String, Long> index = new HashMap<>();
        for (KnowledgeItem item : knowledgeItemMapper.selectList(wrapper)) {
            if (item.getTitle() != null && !item.getTitle().isBlank()) {
                index.put(item.getTitle().trim(), item.getId());
            }
        }
        return index;
    }

    private Long matchObjectId(String object, Map<String, Long> titleIndex) {
        for (Map.Entry<String, Long> entry : titleIndex.entrySet()) {
            if (entry.getKey().contains(object) || object.contains(entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }

    private boolean exists(Long subjectId, String predicate, Long objectId, String objectLabel) {
        QueryWrapper<KnowledgeRelation> wrapper = new QueryWrapper<>();
        wrapper.eq("subject_id", subjectId).eq("predicate", predicate);
        if (objectId != null) {
            wrapper.eq("object_id", objectId);
        } else {
            wrapper.eq("object_label", objectLabel);
        }
        return relationMapper.selectCount(wrapper) > 0;
    }

    /**
     * 正则解析 JSON 数组（容错：畸形/缺失字段丢弃该条，不抛异常）。
     */
    private static List<ExtractedRelation> parseRelations(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        List<ExtractedRelation> result = new ArrayList<>();
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\\{([^{}]*)\\}").matcher(json);
        while (m.find()) {
            String item = m.group(1);
            String predicate = extractField(item, "predicate");
            String object = extractField(item, "object");
            if (predicate == null || object == null || object.isBlank()) {
                continue;
            }
            result.add(new ExtractedRelation(predicate.trim(), object.trim()));
        }
        return result;
    }

    private static String extractField(String jsonItem, String field) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"" + field + "\"\\s*:\\s*\"([^\"]*)\"").matcher(jsonItem);
        return m.find() ? m.group(1) : null;
    }
}
