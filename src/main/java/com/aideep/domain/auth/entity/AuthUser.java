package com.aideep.domain.auth.entity;

import com.aideep.global.entity.BaseEntity;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;

import java.time.Instant;

@Entity
@Table(name = "users")
@AttributeOverride(name = "id", column = @Column(name = "user_id"))
@Getter
public class AuthUser extends BaseEntity {
    @Column(nullable = false, unique = true, length = 255)
    private String email;
    @Column(length = 100)
    private String username;
    @Column(length = 255)
    private String password;

    protected AuthUser() {
    }

    public AuthUser(String email, String password, Instant now) {
        super(now);
        this.email = email;
        this.password = password;
    }

    public void changePassword(String hash, Instant now) {
        password = hash;
        updateTimestamp(now);
    }
}
