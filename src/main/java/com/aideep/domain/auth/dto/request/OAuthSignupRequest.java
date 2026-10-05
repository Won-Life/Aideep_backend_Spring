package com.aideep.domain.auth.dto.request;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record OAuthSignupRequest(
        @NotNull
        String ticket,

        @NotNull
        @Size(min = 2, max = 100)
        String username,

        @AssertTrue(message = "이용약관 동의가 필요합니다.")
        boolean terms,

        @AssertTrue(message = "개인정보 수집 및 이용 동의가 필요합니다.")
        boolean privacy,

        boolean marketing
) {
}
