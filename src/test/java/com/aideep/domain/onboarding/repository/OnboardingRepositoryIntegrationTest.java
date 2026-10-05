package com.aideep.domain.onboarding.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aideep.domain.onboarding.entity.MeetingPlatform;
import com.aideep.domain.onboarding.entity.TermAgreementType;
import com.aideep.domain.onboarding.entity.UsagePurpose;
import com.aideep.domain.onboarding.entity.UserOnboardingProfile;
import com.aideep.domain.onboarding.entity.UserTermAgreement;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Testcontainers
@DataJpaTest(properties = {"spring.jpa.hibernate.ddl-auto=validate", "spring.flyway.enabled=false"})
class OnboardingRepositoryIntegrationTest {

    private static final UUID USER_ID = UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final Instant NOW = Instant.parse("2026-10-05T10:00:00Z");

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine")
            .withInitScript("global/aideep-schema.sql");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry dynamicPropertyRegistry) {
        dynamicPropertyRegistry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        dynamicPropertyRegistry.add("spring.datasource.username", POSTGRES::getUsername);
        dynamicPropertyRegistry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private UserOnboardingProfileRepository userOnboardingProfileRepository;
    @Autowired
    private UserTermAgreementRepository userTermAgreementRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("truncate user_onboarding_profiles, user_term_agreements, users cascade");
        jdbcTemplate.update("insert into users(user_id, email) values (?,?)", USER_ID, "onboarding@example.com");
    }

    @Test
    void persistsUsagePurposeAsEnumAndPlatformsAsArrayInOneRow() {
        UserOnboardingProfile userOnboardingProfile = UserOnboardingProfile.start(USER_ID, NOW);
        userOnboardingProfile.chooseUsagePurpose(UsagePurpose.TEAM_PROJECT, NOW);
        userOnboardingProfile.chooseMeetingPlatforms(
                List.of(MeetingPlatform.ZOOM, MeetingPlatform.GOOGLE_MEET), NOW);
        userOnboardingProfile.complete(NOW);

        userOnboardingProfileRepository.saveAndFlush(userOnboardingProfile);

        UserOnboardingProfile found = userOnboardingProfileRepository
                .findByUserIdAndDeletedAtIsNull(USER_ID).orElseThrow();
        assertThat(found.getUsagePurpose()).isEqualTo(UsagePurpose.TEAM_PROJECT);
        assertThat(found.getMeetingPlatforms())
                .containsExactly(MeetingPlatform.ZOOM, MeetingPlatform.GOOGLE_MEET);
        assertThat(found.getCompletedAt()).isEqualTo(NOW);
        assertThat(jdbcTemplate.queryForObject(
                "select usage_purpose::text from user_onboarding_profiles where user_id=?", String.class, USER_ID))
                .isEqualTo("TEAM_PROJECT");
        assertThat(jdbcTemplate.queryForObject(
                "select array_to_string(meeting_platforms, ',') from user_onboarding_profiles where user_id=?",
                String.class, USER_ID)).isEqualTo("ZOOM,GOOGLE_MEET");
    }

    @Test
    void persistsSkippedAnswersAsNullAndEmptyArray() {
        userOnboardingProfileRepository.saveAndFlush(UserOnboardingProfile.start(USER_ID, NOW));

        UserOnboardingProfile found = userOnboardingProfileRepository
                .findByUserIdAndDeletedAtIsNull(USER_ID).orElseThrow();
        assertThat(found.getUsagePurpose()).isNull();
        assertThat(found.getMeetingPlatforms()).isEmpty();
        assertThat(found.getCompletedAt()).isNull();
    }

    @Test
    void rejectsSecondProfileForSameUser() {
        userOnboardingProfileRepository.saveAndFlush(UserOnboardingProfile.start(USER_ID, NOW));
        assertThat(userOnboardingProfileRepository.existsByUserIdAndDeletedAtIsNull(USER_ID)).isTrue();

        assertThatThrownBy(() -> userOnboardingProfileRepository
                .saveAndFlush(UserOnboardingProfile.start(USER_ID, NOW)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("user_onboarding_profiles_user_id_key");
    }

    @Test
    void persistsEachTermAgreementWithItsOwnAgreementTime() {
        userTermAgreementRepository.saveAndFlush(
                UserTermAgreement.agree(USER_ID, TermAgreementType.TERMS_OF_SERVICE, NOW, NOW));
        userTermAgreementRepository.saveAndFlush(
                UserTermAgreement.agree(USER_ID, TermAgreementType.PRIVACY_POLICY, NOW.plusSeconds(5), NOW));
        userTermAgreementRepository.saveAndFlush(
                UserTermAgreement.decline(USER_ID, TermAgreementType.MARKETING, NOW));

        assertThat(userTermAgreementRepository.findByUserIdAndDeletedAtIsNull(USER_ID)).hasSize(3);
        UserTermAgreement privacy = userTermAgreementRepository
                .findByUserIdAndTermTypeAndDeletedAtIsNull(USER_ID, TermAgreementType.PRIVACY_POLICY).orElseThrow();
        assertThat(privacy.getAgreedAt()).isEqualTo(NOW.plusSeconds(5));
        UserTermAgreement marketing = userTermAgreementRepository
                .findByUserIdAndTermTypeAndDeletedAtIsNull(USER_ID, TermAgreementType.MARKETING).orElseThrow();
        assertThat(marketing.isAgreed()).isFalse();
        assertThat(marketing.getAgreedAt()).isNull();
        assertThat(jdbcTemplate.queryForObject(
                "select term_type::text from user_term_agreements where user_term_agreement_id=?",
                String.class, marketing.getId())).isEqualTo("MARKETING");
    }

    @Test
    void rejectsDuplicateAgreementForSameTerm() {
        userTermAgreementRepository.saveAndFlush(
                UserTermAgreement.agree(USER_ID, TermAgreementType.MARKETING, NOW, NOW));

        assertThatThrownBy(() -> userTermAgreementRepository.saveAndFlush(
                UserTermAgreement.agree(USER_ID, TermAgreementType.MARKETING, NOW, NOW)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void persistsRevocationOnExistingAgreementRow() {
        UserTermAgreement saved = userTermAgreementRepository.saveAndFlush(
                UserTermAgreement.agree(USER_ID, TermAgreementType.MARKETING, NOW, NOW));

        saved.revoke(NOW.plusSeconds(60), NOW.plusSeconds(61));
        userTermAgreementRepository.saveAndFlush(saved);

        UserTermAgreement found = userTermAgreementRepository.findById(saved.getId()).orElseThrow();
        assertThat(found.isAgreed()).isFalse();
        assertThat(found.getAgreedAt()).isEqualTo(NOW);
        assertThat(found.getRevokedAt()).isEqualTo(NOW.plusSeconds(60));
    }
}
