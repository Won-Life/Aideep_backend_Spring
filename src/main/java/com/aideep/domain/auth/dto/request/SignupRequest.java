package com.aideep.domain.auth.dto.request;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record SignupRequest(
        @NotBlank
        @Email
        String email,

        @NotNull
        String password,

        @AssertTrue(message = "이용약관 동의가 필요합니다.")
        boolean termsOfService,

        @AssertTrue(message = "개인정보 수집 및 이용 동의가 필요합니다.")
        boolean privacyPolicy,

        boolean marketing
) {
}
