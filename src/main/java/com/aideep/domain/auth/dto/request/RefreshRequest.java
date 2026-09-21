package com.aideep.domain.auth.dto.request;

import jakarta.validation.constraints.NotNull;

public record RefreshRequest(@NotNull String refreshToken) {
}
