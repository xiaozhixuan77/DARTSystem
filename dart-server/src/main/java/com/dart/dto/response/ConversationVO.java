package com.dart.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@AllArgsConstructor
public class ConversationVO {

    private Long id;
    private String title;
    private String tags;
    private Integer messageCount;
    private LocalDateTime updatedAt;
}
