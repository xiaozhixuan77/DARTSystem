package com.dart.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class ResumeRequest {

    @NotNull(message = "会话 id 不能为空")
    private Long conversationId;
}
