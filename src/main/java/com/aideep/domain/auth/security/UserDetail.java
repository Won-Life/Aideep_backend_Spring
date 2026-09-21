package com.aideep.domain.auth.security;

import com.aideep.domain.auth.entity.AuthUser;

import java.time.Instant;

/**
 * JWT의 사용자 아이디로 DB에서 조회한 현재 사용자 정보를 표현한다.
 */
public final class UserDetail extends CurrentUser {
    private final Instant createdAt;
    private final Instant updatedAt;

    private UserDetail(AuthUser authUser, boolean master) {
        super(authUser.getId(), authUser.getUsername(), authUser.getEmail(), master);
        this.createdAt = authUser.getCreatedAt();
        this.updatedAt = authUser.getUpdatedAt();
    }

    public static UserDetail from(AuthUser authUser, boolean master) {
        return new UserDetail(authUser, master);
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }
}
