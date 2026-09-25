package com.aideep.domain.node.exception;

import static org.assertj.core.api.Assertions.assertThat;

import com.aideep.global.exception.BusinessException;
import com.aideep.global.exception.GlobalExceptionHandler;
import com.aideep.global.response.ResponseHandler;
import java.util.Map;
import java.util.Arrays;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class NodeErrorTest {
    private final GlobalExceptionHandler globalExceptionHandler = new GlobalExceptionHandler();

    @ParameterizedTest
    @CsvSource({
            "INVALID_JSON,NODE-001,400",
            "INVALID_ENVELOPE,NODE-002,400",
            "UNSUPPORTED_VERSION,NODE-003,400",
            "UNSUPPORTED_EVENT_TYPE,NODE-004,400",
            "INVALID_PAYLOAD,NODE-005,400",
            "UNSUPPORTED_NODE_TYPE,NODE-006,400",
            "WORKSPACE_NOT_FOUND,NODE-007,404",
            "NODE_NOT_FOUND,NODE-008,404",
            "STALE_NODE_VERSION,NODE-009,409",
            "INVALID_STREAM_ENTRY,NODE-010,400",
            "PROCESSOR_FAILURE,NODE-011,500"
    })
    void usesCommonFailureResponseAndNumericCode(String name, String code, int status) {
        NodeError nodeError = NodeError.valueOf(name);
        assertThat(nodeError.getCode()).isEqualTo(code);
        assertThat(nodeError.getCode()).matches("NODE-[0-9]{3}");
        assertThat(Arrays.stream(NodeError.values()).filter(value -> value.getCode().equals(code))).hasSize(1);
        var data = Map.of("detail", "validation detail");
        var response = globalExceptionHandler.handleBusinessException(new BusinessException(nodeError, data));

        assertThat(response.getStatusCode().value()).isEqualTo(status);
        assertThat(response.getBody()).isInstanceOfSatisfying(ResponseHandler.class, body -> {
            assertThat(body.getResultType()).isEqualTo(ResponseHandler.ResultType.FAIL);
            assertThat(body.getError().errorCode()).isEqualTo(code);
            assertThat(body.getError().reason()).isEqualTo(nodeError.getReason()).isNotBlank();
            assertThat(body.getError().data()).isEqualTo(data);
            assertThat(body.getSuccess()).isNull();
        });
    }
}
