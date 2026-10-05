package com.aideep.domain.onboarding.service;

import com.aideep.domain.onboarding.dto.TermConsent;
import com.aideep.domain.onboarding.entity.TermAgreementType;
import com.aideep.domain.onboarding.entity.UserTermAgreement;
import com.aideep.domain.onboarding.exception.OnboardingError;
import com.aideep.domain.onboarding.repository.UserTermAgreementRepository;
import com.aideep.global.exception.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 약관 항목별 동의 상태를 기록한다. 다른 도메인(회원가입 등)은 이 서비스를 통해서만 약관 데이터를 다룬다.
 */
@Service
public class UserTermAgreementService {

    private final UserTermAgreementRepository userTermAgreementRepository;
    private final Clock clock;

    public UserTermAgreementService(UserTermAgreementRepository userTermAgreementRepository, Clock clock) {
        this.userTermAgreementRepository = userTermAgreementRepository;
        this.clock = clock;
    }

    /**
     * 회원가입 시점의 약관 동의를 저장한다. 필수 약관에 동의하지 않았으면 아무것도 저장하지 않고 거절한다.
     * 선택 약관은 동의하지 않아도 미동의 상태로 한 행을 남겨, 이후 동의·철회를 같은 행에서 추적한다.
     */
    @Transactional
    public List<UserTermAgreement> record(UUID userId, TermConsent termConsent) {
        Instant now = clock.instant();
        Map<TermAgreementType, Boolean> agreements = termConsent.asMap();
        agreements.forEach((termAgreementType, agreed) -> {
            if (termAgreementType.isRequired() && !Boolean.TRUE.equals(agreed)) {
                throw new BusinessException(OnboardingError.REQUIRED_TERM_NOT_AGREED);
            }
        });
        return agreements.entrySet()
                .stream()
                .map(agreement -> apply(userId, agreement.getKey(), agreement.getValue(), now))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<UserTermAgreement> findAgreements(UUID userId) {
        return userTermAgreementRepository.findByUserIdAndDeletedAtIsNull(userId);
    }

    private UserTermAgreement apply(UUID userId, TermAgreementType termAgreementType, boolean agreed, Instant now) {
        UserTermAgreement userTermAgreement = userTermAgreementRepository
                .findByUserIdAndTermTypeAndDeletedAtIsNull(userId, termAgreementType)
                .orElse(null);
        if (userTermAgreement == null) {
            return userTermAgreementRepository.save(agreed
                    ? UserTermAgreement.agree(userId, termAgreementType, now, now)
                    : UserTermAgreement.decline(userId, termAgreementType, now));
        }
        if (agreed) {
            userTermAgreement.reagree(now, now);
        } else {
            userTermAgreement.revoke(now, now);
        }
        return userTermAgreementRepository.save(userTermAgreement);
    }
}
