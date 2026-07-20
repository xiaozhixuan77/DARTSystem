package com.dart.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dart.entity.KnowledgeChunk;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface KnowledgeChunkMapper extends BaseMapper<KnowledgeChunk> {

    default int insertKnowledgeChunk(KnowledgeChunk chunk) {
        return insert(chunk);
    }
}
