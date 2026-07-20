package com.dart.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dart.entity.KnowledgeItem;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface KnowledgeItemMapper extends BaseMapper<KnowledgeItem> {

    default int insertKnowledgeItem(KnowledgeItem item) {
        return insert(item);
    }

    default int updateKnowledgeItem(KnowledgeItem item) {
        return updateById(item);
    }
}
