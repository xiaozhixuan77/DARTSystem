package com.dart.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dart.entity.Message;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface MessageMapper extends BaseMapper<Message> {

    default int insertMessage(Message message) {
        return insert(message);
    }
}
