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

    /** 가입 시점에 닉네임까지 함께 저장하는 생성자. */
    public AuthUser(String email, String password, String username, Instant now) {
        this(email, password, now);
        this.username = username;
    }

    public void changePassword(String hash, Instant now) {
        password = hash;
        updateTimestamp(now);
    }

    public void setUsername(String username) {
        this.username = username;
    }

    /** 닉네임을 지정한다. 로컬 가입은 null로 두고 온보딩에서, 구글 가입은 가입 시점에 설정한다. */
    public void changeUsername(String username, Instant now) {
        this.username = username;
        updateTimestamp(now);
    }
}
