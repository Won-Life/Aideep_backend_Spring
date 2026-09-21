package com.aideep.domain.auth.dto.response;

import java.time.Instant;

public record OAuthLinkResponse(String provider, String email, Instant created_at) {
}
