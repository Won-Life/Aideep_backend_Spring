package com.aideep.domain.auth.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record PasswordRequest(String currentPassword, @NotNull @Size(min = 4) String newPassword) {
}
