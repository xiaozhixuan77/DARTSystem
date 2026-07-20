package com.dart.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dart.entity.RefreshToken;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface RefreshTokenMapper extends BaseMapper<RefreshToken> {

    /** 包装 insert(T) 避免 MyBatis-Plus 3.5.9 重载方法与 Mockito any() 冲突 */
    default int insertToken(RefreshToken token) {
        return insert(token);
    }

    default int updateToken(RefreshToken token) {
        return updateById(token);
    }
}
