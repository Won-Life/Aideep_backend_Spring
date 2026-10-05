package com.aideep.domain.auth.security;

import java.util.UUID;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * 인증된 JWT 클레임을 애플리케이션에서 사용할 사용자 객체로 표현한다.
 */
public class CurrentUser {
    private final UUID userId;
    private final String email;
    private final boolean master;

    public CurrentUser(UUID userId, String email, boolean master) {
        this.userId = userId;
        this.email = email;
        this.master = master;
    }

    public static CurrentUser from(Jwt jwt) {
        return new CurrentUser(
                UUID.fromString(jwt.getClaimAsString("user_id")),
                jwt.getClaimAsString("email"),
                Boolean.TRUE.equals(jwt.getClaims().get("isMaster")));
    }

    public UUID userId() {
        return userId;
    }

    public String email() {
        return email;
    }

    public boolean master() {
        return master;
    }
}
