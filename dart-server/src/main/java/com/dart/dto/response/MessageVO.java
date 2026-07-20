package com.dart.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@AllArgsConstructor
public class MessageVO {

    private Long id;
    private String role;
    private String content;
    private String metadata;
    private LocalDateTime createdAt;
}
