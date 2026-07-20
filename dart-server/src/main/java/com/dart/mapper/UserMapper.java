package com.dart.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dart.entity.User;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface UserMapper extends BaseMapper<User> {

    /** 包装 insert(T) 避免 MyBatis-Plus 3.5.9 重载方法与 Mockito any() 冲突 */
    default int insertUser(User user) {
        return insert(user);
    }

    /** 包装 updateById(T) 同上 */
    default int updateUser(User user) {
        return updateById(user);
    }
}
