package com.aideep.domain.onboarding.entity;

import com.aideep.global.entity.BaseEntity;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * 약관 항목 한 건에 대한 동의 상태. 항목별로 동의 여부와 동의 시각을 따로 남기고,
 * 선택 약관의 철회·재동의도 같은 행에서 시각을 갱신해 추적한다.
 */
@Entity
@Table(name = "user_term_agreements")
@AttributeOverride(name = "id", column = @Column(name = "user_term_agreement_id"))
@Getter
public class UserTermAgreement extends BaseEntity {

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "term_type", nullable = false, updatable = false,
            columnDefinition = "term_agreement_type_enum")
    private TermAgreementType termType;

    @Column(name = "agreed", nullable = false)
    private boolean agreed;

    @Column(name = "agreed_at")
    private Instant agreedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    protected UserTermAgreement() {
    }

    private UserTermAgreement(UUID userId, TermAgreementType termType, Instant now) {
        super(now);
        this.userId = userId;
        this.termType = termType;
    }

    public static UserTermAgreement agree(UUID userId, TermAgreementType termType, Instant agreedAt, Instant now) {
        UserTermAgreement userTermAgreement = new UserTermAgreement(userId, termType, now);
        userTermAgreement.agreed = true;
        userTermAgreement.agreedAt = agreedAt;
        return userTermAgreement;
    }

    /** 선택 약관을 동의하지 않은 상태로 남긴다. 필수 약관에는 사용하지 않는다. */
    public static UserTermAgreement decline(UUID userId, TermAgreementType termType, Instant now) {
        return new UserTermAgreement(userId, termType, now);
    }

    public void reagree(Instant agreedAt, Instant now) {
        agreed = true;
        this.agreedAt = agreedAt;
        revokedAt = null;
        updateTimestamp(now);
    }

    public void revoke(Instant revokedAt, Instant now) {
        agreed = false;
        this.revokedAt = revokedAt;
        updateTimestamp(now);
    }
}
