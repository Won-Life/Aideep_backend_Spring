package com.aideep.domain.onboarding.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aideep.domain.onboarding.dto.TermConsent;
import com.aideep.domain.onboarding.entity.TermAgreementType;
import com.aideep.domain.onboarding.entity.UserTermAgreement;
import com.aideep.domain.onboarding.exception.OnboardingError;
import com.aideep.domain.onboarding.repository.UserTermAgreementRepository;
import com.aideep.global.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

class UserTermAgreementServiceTest {

    private static final UUID USER_ID = UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final Instant NOW = Instant.parse("2026-10-05T10:00:00Z");

    private UserTermAgreementRepository userTermAgreementRepository;
    private UserTermAgreementService userTermAgreementService;

    @BeforeEach
    void setUp() {
        userTermAgreementRepository = mock(UserTermAgreementRepository.class);
        userTermAgreementService = new UserTermAgreementService(
                userTermAgreementRepository, Clock.fixed(NOW, ZoneOffset.UTC));
        when(userTermAgreementRepository.findByUserIdAndTermTypeAndDeletedAtIsNull(any(), any()))
                .thenReturn(Optional.empty());
        when(userTermAgreementRepository.save(any(UserTermAgreement.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void recordsOneRowPerTermWithAgreementTime() {
        List<UserTermAgreement> saved = userTermAgreementService.record(USER_ID, new TermConsent(true, true, true));

        assertThat(saved).hasSize(3);
        assertThat(saved).extracting(UserTermAgreement::getTermType).containsExactly(
                TermAgreementType.TERMS_OF_SERVICE, TermAgreementType.PRIVACY_POLICY, TermAgreementType.MARKETING);
        assertThat(saved).allSatisfy(userTermAgreement -> {
            assertThat(userTermAgreement.getUserId()).isEqualTo(USER_ID);
            assertThat(userTermAgreement.isAgreed()).isTrue();
            assertThat(userTermAgreement.getAgreedAt()).isEqualTo(NOW);
        });
    }

    @Test
    void keepsDeclinedMarketingAsNotAgreedRow() {
        List<UserTermAgreement> saved = userTermAgreementService.record(USER_ID, TermConsent.requiredOnly());

        UserTermAgreement marketing = saved.getLast();
        assertThat(marketing.getTermType()).isEqualTo(TermAgreementType.MARKETING);
        assertThat(marketing.isAgreed()).isFalse();
        assertThat(marketing.getAgreedAt()).isNull();
    }

    @Test
    void rejectsSignupWithoutRequiredTermsBeforeSavingAnything() {
        assertThatThrownBy(() -> userTermAgreementService.record(USER_ID, new TermConsent(true, false, true)))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(OnboardingError.REQUIRED_TERM_NOT_AGREED));

        verify(userTermAgreementRepository, never()).save(any(UserTermAgreement.class));
    }

    @Test
    void reusesExistingRowWhenTermIsRecordedAgain() {
        UserTermAgreement existing = UserTermAgreement.agree(
                USER_ID, TermAgreementType.MARKETING, NOW.minusSeconds(600), NOW.minusSeconds(600));
        existing.revoke(NOW.minusSeconds(300), NOW.minusSeconds(300));
        when(userTermAgreementRepository.findByUserIdAndTermTypeAndDeletedAtIsNull(
                USER_ID, TermAgreementType.MARKETING)).thenReturn(Optional.of(existing));

        List<UserTermAgreement> saved = userTermAgreementService.record(USER_ID, new TermConsent(true, true, true));

        assertThat(saved.getLast()).isSameAs(existing);
        assertThat(existing.isAgreed()).isTrue();
        assertThat(existing.getAgreedAt()).isEqualTo(NOW);
        assertThat(existing.getRevokedAt()).isNull();
    }
}
