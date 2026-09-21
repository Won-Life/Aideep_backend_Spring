package com.aideep.domain.auth.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record SignupRequest(@NotBlank @Email String email, @NotNull String password,
                            @NotNull String name, @NotNull String phone) {
}
