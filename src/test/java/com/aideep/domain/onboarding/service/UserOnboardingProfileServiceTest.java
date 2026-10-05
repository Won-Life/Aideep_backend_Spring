package com.aideep.domain.onboarding.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.aideep.domain.onboarding.entity.MeetingPlatform;
import com.aideep.domain.onboarding.entity.UsagePurpose;
import com.aideep.domain.onboarding.entity.UserOnboardingProfile;
import com.aideep.domain.onboarding.repository.UserOnboardingProfileRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

class UserOnboardingProfileServiceTest {

    private static final UUID USER_ID = UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final Instant NOW = Instant.parse("2026-10-05T10:00:00Z");

    private UserOnboardingProfileRepository userOnboardingProfileRepository;
    private UserOnboardingProfileService userOnboardingProfileService;

    @BeforeEach
    void setUp() {
        userOnboardingProfileRepository = mock(UserOnboardingProfileRepository.class);
        userOnboardingProfileService = new UserOnboardingProfileService(
                userOnboardingProfileRepository, Clock.fixed(NOW, ZoneOffset.UTC));
        when(userOnboardingProfileRepository.findByUserIdAndDeletedAtIsNull(USER_ID)).thenReturn(Optional.empty());
        when(userOnboardingProfileRepository.save(any(UserOnboardingProfile.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void createsProfileWithAnswersAndCompletionTime() {
        UserOnboardingProfile saved = userOnboardingProfileService.save(USER_ID, UsagePurpose.TEAM_PROJECT,
                List.of(MeetingPlatform.ZOOM, MeetingPlatform.GOOGLE_MEET));

        assertThat(saved.getUserId()).isEqualTo(USER_ID);
        assertThat(saved.getUsagePurpose()).isEqualTo(UsagePurpose.TEAM_PROJECT);
        assertThat(saved.getMeetingPlatforms())
                .containsExactly(MeetingPlatform.ZOOM, MeetingPlatform.GOOGLE_MEET);
        assertThat(saved.getCompletedAt()).isEqualTo(NOW);
    }

    @Test
    void storesSkippedAnswersAsNullAndEmptySelection() {
        UserOnboardingProfile saved = userOnboardingProfileService.save(USER_ID, null, null);

        assertThat(saved.getUsagePurpose()).isNull();
        assertThat(saved.getMeetingPlatforms()).isEmpty();
        assertThat(saved.isCompleted()).isTrue();
    }

    @Test
    void updatesExistingProfileInsteadOfCreatingSecondRow() {
        UserOnboardingProfile existing = UserOnboardingProfile.start(USER_ID, NOW.minusSeconds(600));
        existing.chooseUsagePurpose(UsagePurpose.STUDY_CLUB, NOW.minusSeconds(600));
        existing.chooseMeetingPlatforms(List.of(MeetingPlatform.OFFLINE), NOW.minusSeconds(600));
        existing.complete(NOW.minusSeconds(600));
        when(userOnboardingProfileRepository.findByUserIdAndDeletedAtIsNull(USER_ID))
                .thenReturn(Optional.of(existing));

        UserOnboardingProfile saved = userOnboardingProfileService.save(USER_ID, UsagePurpose.COMPANY_WORK,
                List.of(MeetingPlatform.ZOOM));

        assertThat(saved).isSameAs(existing);
        assertThat(saved.getUsagePurpose()).isEqualTo(UsagePurpose.COMPANY_WORK);
        assertThat(saved.getMeetingPlatforms()).containsExactly(MeetingPlatform.ZOOM);
        assertThat(saved.getCompletedAt()).as("완료 시각은 처음 저장 시점을 유지한다")
                .isEqualTo(NOW.minusSeconds(600));
        assertThat(saved.getUpdatedAt()).isEqualTo(NOW);
    }

    @Test
    void removesDuplicateSelectionsBeforeSaving() {
        UserOnboardingProfile saved = userOnboardingProfileService.save(USER_ID, UsagePurpose.OTHER,
                List.of(MeetingPlatform.OTHER, MeetingPlatform.OTHER, MeetingPlatform.OFFLINE));

        assertThat(saved.getMeetingPlatforms()).containsExactly(MeetingPlatform.OTHER, MeetingPlatform.OFFLINE);
    }
}
