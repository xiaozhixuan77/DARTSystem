package com.dart.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** chat API 运行时配置（设置页更换，单行 id=1；无登录模式全局一份） */
@Data
@TableName("app_api_config")
public class AppApiConfig {

    @TableId(type = IdType.INPUT)
    private Long id;
    private String chatBaseUrl;
    private String chatModel;
    private String chatApiKey;
    private LocalDateTime updatedAt;
}
