package com.dart.exception;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ErrorCodeTest {

    @Test
    void errorCodes_areUnique() {
        Set<Integer> codes = new HashSet<>();
        for (ErrorCode ec : ErrorCode.values()) {
            codes.add(ec.getCode());
        }
        assertThat(codes).hasSize(ErrorCode.values().length);
    }

    @Test
    void errorCodes_haveMessages() {
        for (ErrorCode ec : ErrorCode.values()) {
            assertThat(ec.getMessage())
                    .as("ErrorCode %s should have a non-blank message", ec.name())
                    .isNotBlank();
        }
    }

    @Test
    void errorCodes_count_matches() {
        assertThat(ErrorCode.values()).hasSize(31);
    }

    @Test
    void errorCodes_byCategory_1xxx() {
        assertThat(ErrorCode.SUCCESS.getCode()).isEqualTo(200);
        assertThat(ErrorCode.BAD_REQUEST.getCode()).isEqualTo(400);
        assertThat(ErrorCode.UNAUTHORIZED.getCode()).isEqualTo(401);
        assertThat(ErrorCode.INTERNAL_ERROR.getCode()).isEqualTo(500);
    }

    @Test
    void errorCodes_byCategory_2xxx() {
        assertThat(ErrorCode.USERNAME_EXISTS.getCode()).isBetween(2000, 2999);
        assertThat(ErrorCode.REFRESH_TOKEN_EXPIRED.getCode()).isBetween(2000, 2999);
    }

    @Test
    void errorCodes_byCategory_3xxx() {
        assertThat(ErrorCode.NON_TECH_QUERY.getCode()).isBetween(3000, 3999);
        assertThat(ErrorCode.NO_QUALITY_RESULTS.getCode()).isBetween(3000, 3999);
    }

    @Test
    void errorCodes_byCategory_4xxx() {
        assertThat(ErrorCode.AGENT_EXECUTION_ERROR.getCode()).isBetween(4000, 4999);
        assertThat(ErrorCode.SCORING_ERROR.getCode()).isBetween(4000, 4999);
    }

    @Test
    void errorCodes_byCategory_5xxx() {
        assertThat(ErrorCode.KNOWLEDGE_NOT_FOUND.getCode()).isBetween(5000, 5999);
        assertThat(ErrorCode.REBUILD_IN_PROGRESS.getCode()).isBetween(5000, 5999);
    }

    @Test
    void errorCodes_byCategory_6xxx() {
        assertThat(ErrorCode.CONVERSATION_NOT_FOUND.getCode()).isBetween(6000, 6999);
        assertThat(ErrorCode.MESSAGE_SAVE_ERROR.getCode()).isBetween(6000, 6999);
    }
}
