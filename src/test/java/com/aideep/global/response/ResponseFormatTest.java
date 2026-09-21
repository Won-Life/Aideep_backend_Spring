package com.aideep.global.response;

import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aideep.global.exception.BusinessException;
import com.aideep.global.exception.GlobalErrorCode;
import com.aideep.global.exception.GlobalExceptionHandler;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * NestJS 서버와 동일한 JSON 계약이 유지되는지 검증한다. error / success는 값이 null이어도 키가 남아 있어야 프론트엔드 파서가 두 서버를 동일하게 처리할 수 있다.
 */
class ResponseFormatTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new TestController())
                .setControllerAdvice(new ResponseWrappingAdvice(), new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("DTO를 반환하면 SUCCESS 포맷으로 자동 래핑된다")
    void wrapsSuccess() throws Exception {
        mockMvc.perform(get("/test/success"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").value(hasKey("error")))
                .andExpect(jsonPath("$.resultType").value("SUCCESS"))
                .andExpect(jsonPath("$.error").value(nullValue()))
                .andExpect(jsonPath("$.success.name").value("아이딥"));
    }

    @Test
    @DisplayName("BusinessException은 해당 상태 코드와 FAIL 포맷으로 변환된다")
    void convertsBusinessException() throws Exception {
        mockMvc.perform(get("/test/business"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$").value(hasKey("success")))
                .andExpect(jsonPath("$.resultType").value("FAIL"))
                .andExpect(jsonPath("$.error.errorCode").value("COMMON404"))
                .andExpect(jsonPath("$.error.reason").value("요청한 리소스를 찾을 수 없습니다."))
                .andExpect(jsonPath("$.error.data").value(nullValue()))
                .andExpect(jsonPath("$.success").value(nullValue()));
    }

    @Test
    @DisplayName("검증 실패는 400과 함께 error.data에 필드별 메시지를 담는다")
    void convertsValidationFailure() throws Exception {
        mockMvc.perform(post("/test/validation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.resultType").value("FAIL"))
                .andExpect(jsonPath("$.error.errorCode").value("VALID400"))
                .andExpect(jsonPath("$.error.data.name").value("이름은 필수입니다."))
                .andExpect(jsonPath("$.success").value(nullValue()));
    }

    @Test
    @DisplayName("컨트롤러가 이미 ResponseHandler를 반환하면 이중 래핑하지 않는다")
    void doesNotDoubleWrap() throws Exception {
        mockMvc.perform(get("/test/already-wrapped"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultType").value("SUCCESS"))
                .andExpect(jsonPath("$.success.name").value("아이딥"))
                .andExpect(jsonPath("$.success.resultType").doesNotExist());
    }

    @Test
    @DisplayName("예상치 못한 예외는 500 COMMON500으로 변환되고 내부 정보를 노출하지 않는다")
    void convertsUnexpectedException() throws Exception {
        mockMvc.perform(get("/test/unexpected"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.resultType").value("FAIL"))
                .andExpect(jsonPath("$.error.errorCode").value("COMMON500"))
                .andExpect(jsonPath("$.error.reason").value("서버 내부 오류가 발생했습니다."));
    }

    @RestController
    static class TestController {

        record UserResponse(String name) {
        }

        record UserRequest(@NotBlank(message = "이름은 필수입니다.") String name) {
        }

        @GetMapping("/test/success")
        UserResponse success() {
            return new UserResponse("아이딥");
        }

        @GetMapping("/test/business")
        UserResponse business() {
            throw new BusinessException(GlobalErrorCode.NOT_FOUND);
        }

        @PostMapping("/test/validation")
        UserResponse validation(@RequestBody @Valid UserRequest request) {
            return new UserResponse(request.name());
        }

        @GetMapping("/test/already-wrapped")
        ResponseHandler<UserResponse> alreadyWrapped() {
            return ResponseHandler.success(new UserResponse("아이딥"));
        }

        @GetMapping("/test/unexpected")
        UserResponse unexpected() {
            throw new IllegalStateException("내부 상태 오류");
        }
    }
}
