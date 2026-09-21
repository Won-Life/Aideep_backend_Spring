package com.aideep.domain.auth.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record VerifyEmailRequest(@NotBlank @Email String email, @NotNull java.math.BigDecimal code) {
    public VerifyEmailRequest(String email, int code) {
        this(email, java.math.BigDecimal.valueOf(code));
    }
}
