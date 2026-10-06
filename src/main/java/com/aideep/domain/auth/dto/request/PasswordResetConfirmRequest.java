package com.aideep.domain.auth.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record PasswordResetConfirmRequest(@NotBlank String token, @NotNull @Size(min = 4) String newPassword) {
}
