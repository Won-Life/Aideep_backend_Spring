package com.aideep.domain.auth.dto;

import com.aideep.domain.auth.dto.response.TokensResponse;

public record OAuthResult(String kind, TokensResponse tokens, String ticket) {
    public static OAuthResult login(TokensResponse tokens) {
        return new OAuthResult("login", tokens, null);
    }

    public static OAuthResult linked() {
        return new OAuthResult("linked", null, null);
    }

    public static OAuthResult signup(String ticket) {
        return new OAuthResult("signup_required", null, ticket);
    }
}
