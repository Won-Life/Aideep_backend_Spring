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
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

/**
 * 온보딩 설문 답변. 사용자당 한 행이며, 사용 목적(단일 선택)과 주 회의처(복수 선택)를 함께 보관한다.
 * 두 단계 모두 건너뛸 수 있으므로 답변은 비어 있을 수 있다.
 */
@Entity
@Table(name = "user_onboarding_profiles")
@AttributeOverride(name = "id", column = @Column(name = "user_onboarding_profile_id"))
@Getter
public class UserOnboardingProfile extends BaseEntity {

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "usage_purpose", columnDefinition = "usage_purpose_enum")
    private UsagePurpose usagePurpose;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "meeting_platforms", nullable = false, columnDefinition = "varchar(32)[]")
    private List<MeetingPlatform> meetingPlatforms = new ArrayList<>();

    @Column(name = "completed_at")
    private Instant completedAt;

    protected UserOnboardingProfile() {
    }

    private UserOnboardingProfile(UUID userId, Instant now) {
        super(now);
        this.userId = userId;
    }

    public static UserOnboardingProfile start(UUID userId, Instant now) {
        return new UserOnboardingProfile(userId, now);
    }

    /** 사용 목적을 기록한다. 건너뛴 경우 null을 넘겨 미응답으로 남긴다. */
    public void chooseUsagePurpose(UsagePurpose usagePurpose, Instant now) {
        this.usagePurpose = usagePurpose;
        updateTimestamp(now);
    }

    /** 주 회의처를 기록한다. 중복은 제거하고 선택 순서는 유지한다. */
    public void chooseMeetingPlatforms(Collection<MeetingPlatform> meetingPlatforms, Instant now) {
        this.meetingPlatforms = meetingPlatforms == null
                ? new ArrayList<>()
                : new ArrayList<>(new LinkedHashSet<>(meetingPlatforms));
        updateTimestamp(now);
    }

    /** 온보딩을 끝낸 시각을 남긴다. 이미 완료한 프로필은 처음 완료 시각을 유지한다. */
    public void complete(Instant now) {
        if (completedAt == null) {
            completedAt = now;
        }
        updateTimestamp(now);
    }

    public boolean isCompleted() {
        return completedAt != null;
    }

    public List<MeetingPlatform> getMeetingPlatforms() {
        return List.copyOf(meetingPlatforms);
    }
}
