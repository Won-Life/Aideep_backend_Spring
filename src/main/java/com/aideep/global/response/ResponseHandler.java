package com.aideep.global.response;

import com.aideep.global.exception.ErrorCode;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;

/**
 * NestJS 서버와 공유하는 공통 응답 포맷.
 *
 * <pre>
 * 성공: { "resultType": "SUCCESS", "error": null, "success": { ... } }
 * 실패: { "resultType": "FAIL", "error": { "errorCode", "reason", "data" }, "success": null }
 * </pre>
 * <p>
 * error / success 는 값이 null이어도 키가 JSON에 남아야 NestJS 응답과 동일하다. 따라서 이 클래스에 {@code @JsonInclude(NON_NULL)}을 붙이면 안 된다.
 */
@Getter
@JsonPropertyOrder({"resultType", "error", "success"})
public class ResponseHandler<T> {

    public enum ResultType {
        SUCCESS, FAIL
    }

    @Schema(description = "응답 성공 여부", example = "SUCCESS")
    private final ResultType resultType;

    @Schema(description = "실패 시에만 채워지는 에러 정보", nullable = true)
    private final ErrorBody error;

    @Schema(description = "실제 사용할 데이터", nullable = true)
    private final T success;

    private ResponseHandler(ResultType resultType, ErrorBody error, T success) {
        this.resultType = resultType;
        this.error = error;
        this.success = success;
    }

    public static <T> ResponseHandler<T> success(T data) {
        return new ResponseHandler<>(ResultType.SUCCESS, null, data);
    }

    public static ResponseHandler<Object> fail(ErrorCode errorCode, Object data) {
        return fail(errorCode.getCode(), errorCode.getReason(), data);
    }

    public static ResponseHandler<Object> fail(String errorCode, String reason, Object data) {
        return new ResponseHandler<>(ResultType.FAIL, new ErrorBody(errorCode, reason, data), null);
    }

    public record ErrorBody(
            @Schema(description = "프론트엔드가 분기에 사용하는 에러 코드", example = "COMMON400")
            String errorCode,

            @Schema(description = "에러 사유", example = "잘못된 요청입니다.")
            String reason,

            @Schema(description = "에러 부가 정보. 문자열 또는 필드별 메시지 등", nullable = true)
            Object data
    ) {
    }
}
