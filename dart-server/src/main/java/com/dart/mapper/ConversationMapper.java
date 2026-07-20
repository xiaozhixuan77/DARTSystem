package com.dart.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dart.entity.Conversation;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ConversationMapper extends BaseMapper<Conversation> {

    default int insertConversation(Conversation conversation) {
        return insert(conversation);
    }

    default int updateConversation(Conversation conversation) {
        return updateById(conversation);
    }
}
