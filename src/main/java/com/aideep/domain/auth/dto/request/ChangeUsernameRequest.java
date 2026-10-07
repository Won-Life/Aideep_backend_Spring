package com.aideep.domain.auth.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ChangeUsernameRequest(@NotBlank @Size(max = 100) String username) {
}
