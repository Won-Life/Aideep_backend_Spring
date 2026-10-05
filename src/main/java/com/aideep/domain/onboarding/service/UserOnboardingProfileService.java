package com.aideep.domain.onboarding.service;

import com.aideep.domain.onboarding.entity.MeetingPlatform;
import com.aideep.domain.onboarding.entity.UsagePurpose;
import com.aideep.domain.onboarding.entity.UserOnboardingProfile;
import com.aideep.domain.onboarding.repository.UserOnboardingProfileRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

/**
 * 온보딩 설문 답변을 저장한다. 다른 도메인은 이 서비스를 통해서만 온보딩 프로필을 다룬다.
 */
@Service
public class UserOnboardingProfileService {

    private final UserOnboardingProfileRepository userOnboardingProfileRepository;
    private final Clock clock;

    public UserOnboardingProfileService(UserOnboardingProfileRepository userOnboardingProfileRepository, Clock clock) {
        this.userOnboardingProfileRepository = userOnboardingProfileRepository;
        this.clock = clock;
    }

    /**
     * 설문 답변을 저장한다. 사용자당 프로필은 한 행이므로 이미 있으면 같은 행을 갱신한다.
     * 두 단계 모두 건너뛸 수 있어 null·빈 선택도 그대로 저장하고, 완료 시각은 처음 저장한 시점을 유지한다.
     */
    @Transactional
    public UserOnboardingProfile save(UUID userId, UsagePurpose usagePurpose,
                                      Collection<MeetingPlatform> meetingPlatforms) {
        Instant now = clock.instant();
        UserOnboardingProfile userOnboardingProfile = userOnboardingProfileRepository
                .findByUserIdAndDeletedAtIsNull(userId)
                .orElseGet(() -> UserOnboardingProfile.start(userId, now));
        userOnboardingProfile.chooseUsagePurpose(usagePurpose, now);
        userOnboardingProfile.chooseMeetingPlatforms(meetingPlatforms, now);
        userOnboardingProfile.complete(now);
        return userOnboardingProfileRepository.save(userOnboardingProfile);
    }

    @Transactional(readOnly = true)
    public Optional<UserOnboardingProfile> find(UUID userId) {
        return userOnboardingProfileRepository.findByUserIdAndDeletedAtIsNull(userId);
    }
}
