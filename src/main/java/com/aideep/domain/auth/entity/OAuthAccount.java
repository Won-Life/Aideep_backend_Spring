package com.aideep.domain.auth.entity;

import com.aideep.global.entity.BaseEntity;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "oauth_accounts", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"provider", "provider_user_id"}),
        @UniqueConstraint(columnNames = {"user_id", "provider"})})
@AttributeOverride(name = "id", column = @Column(name = "oauth_account_id"))
@Getter
public class OAuthAccount extends BaseEntity {
    @Column(name = "user_id", nullable = false)
    private UUID userId;
    @Column(nullable = false, length = 50)
    private String provider;
    @Column(name = "provider_user_id", nullable = false, length = 255)
    private String providerUserId;
    @Column(length = 255)
    private String email;

    protected OAuthAccount() {
    }

    public OAuthAccount(UUID userId, String provider, String subject, String email, Instant now) {
        super(now);
        this.userId = userId;
        this.provider = provider;
        providerUserId = subject;
        this.email = email;
    }

    public void revive(String email, Instant now) {
        this.email = email;
        restore(now);
    }

    public void unlink(Instant now) {
        markDeleted(now);
    }
}
