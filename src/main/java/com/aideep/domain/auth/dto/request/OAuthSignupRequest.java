package com.aideep.domain.auth.dto.request;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record OAuthSignupRequest(@NotNull String ticket, @NotNull @Size(min = 2, max = 100) String username,
                                 @NotNull @AssertTrue(message = "약관 동의가 필요합니다.") Boolean agreedToTerms) {
}
