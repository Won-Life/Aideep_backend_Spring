package com.aideep.domain.onboarding.entity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

class UserOnboardingProfileTest {

    private static final UUID USER_ID = UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final Instant NOW = Instant.parse("2026-10-05T10:00:00Z");

    @Test
    void startsWithoutAnswers() {
        UserOnboardingProfile userOnboardingProfile = UserOnboardingProfile.start(USER_ID, NOW);

        assertThat(userOnboardingProfile.getUserId()).isEqualTo(USER_ID);
        assertThat(userOnboardingProfile.getUsagePurpose()).isNull();
        assertThat(userOnboardingProfile.getMeetingPlatforms()).isEmpty();
        assertThat(userOnboardingProfile.isCompleted()).isFalse();
    }

    @Test
    void keepsSelectionOrderAndRemovesDuplicatePlatforms() {
        UserOnboardingProfile userOnboardingProfile = UserOnboardingProfile.start(USER_ID, NOW);

        userOnboardingProfile.chooseMeetingPlatforms(
                List.of(MeetingPlatform.ZOOM, MeetingPlatform.GOOGLE_MEET, MeetingPlatform.ZOOM), NOW.plusSeconds(1));

        assertThat(userOnboardingProfile.getMeetingPlatforms())
                .containsExactly(MeetingPlatform.ZOOM, MeetingPlatform.GOOGLE_MEET);
        assertThat(userOnboardingProfile.getUpdatedAt()).isEqualTo(NOW.plusSeconds(1));
    }

    @Test
    void treatsSkippedStepsAsNoAnswer() {
        UserOnboardingProfile userOnboardingProfile = UserOnboardingProfile.start(USER_ID, NOW);
        userOnboardingProfile.chooseUsagePurpose(UsagePurpose.TEAM_PROJECT, NOW.plusSeconds(1));
        userOnboardingProfile.chooseMeetingPlatforms(List.of(MeetingPlatform.OFFLINE), NOW.plusSeconds(2));

        userOnboardingProfile.chooseUsagePurpose(null, NOW.plusSeconds(3));
        userOnboardingProfile.chooseMeetingPlatforms(null, NOW.plusSeconds(4));

        assertThat(userOnboardingProfile.getUsagePurpose()).isNull();
        assertThat(userOnboardingProfile.getMeetingPlatforms()).isEmpty();
    }

    @Test
    void completeKeepsFirstCompletionTime() {
        UserOnboardingProfile userOnboardingProfile = UserOnboardingProfile.start(USER_ID, NOW);

        userOnboardingProfile.complete(NOW.plusSeconds(10));
        userOnboardingProfile.complete(NOW.plusSeconds(20));

        assertThat(userOnboardingProfile.isCompleted()).isTrue();
        assertThat(userOnboardingProfile.getCompletedAt()).isEqualTo(NOW.plusSeconds(10));
        assertThat(userOnboardingProfile.getUpdatedAt()).isEqualTo(NOW.plusSeconds(20));
    }

    @Test
    void returnedPlatformsAreNotModifiableFromOutside() {
        UserOnboardingProfile userOnboardingProfile = UserOnboardingProfile.start(USER_ID, NOW);
        userOnboardingProfile.chooseMeetingPlatforms(List.of(MeetingPlatform.ZOOM), NOW);

        List<MeetingPlatform> meetingPlatforms = userOnboardingProfile.getMeetingPlatforms();

        assertThat(meetingPlatforms).isUnmodifiable();
    }
}
