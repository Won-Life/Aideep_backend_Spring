package com.aideep.domain.onboarding.repository;

import com.aideep.domain.onboarding.entity.UserOnboardingProfile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface UserOnboardingProfileRepository extends JpaRepository<UserOnboardingProfile, UUID> {

    Optional<UserOnboardingProfile> findByUserIdAndDeletedAtIsNull(UUID userId);

    boolean existsByUserIdAndDeletedAtIsNull(UUID userId);
}
