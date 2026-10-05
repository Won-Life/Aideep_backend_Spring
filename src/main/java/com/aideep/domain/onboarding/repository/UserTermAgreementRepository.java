package com.aideep.domain.onboarding.repository;

import com.aideep.domain.onboarding.entity.TermAgreementType;
import com.aideep.domain.onboarding.entity.UserTermAgreement;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserTermAgreementRepository extends JpaRepository<UserTermAgreement, UUID> {

    List<UserTermAgreement> findByUserIdAndDeletedAtIsNull(UUID userId);

    Optional<UserTermAgreement> findByUserIdAndTermTypeAndDeletedAtIsNull(UUID userId, TermAgreementType termType);
}
