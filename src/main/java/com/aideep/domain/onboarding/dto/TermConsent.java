package com.aideep.domain.onboarding.dto;

import com.aideep.domain.onboarding.entity.TermAgreementType;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 약관 항목별 동의 여부. 도메인 경계에서 주고받는 입력이므로 특정 API 요청 형식에 묶지 않는다.
 * 필수 약관(이용약관, 개인정보 수집·이용)은 true여야 하고 마케팅 수신은 선택이다.
 */
public record TermConsent(boolean termsOfService, boolean privacyPolicy, boolean marketing) {

    /** 필수 약관만 동의하고 마케팅 수신은 거부한 상태. */
    public static TermConsent requiredOnly() {
        return new TermConsent(true, true, false);
    }

    public Map<TermAgreementType, Boolean> asMap() {
        Map<TermAgreementType, Boolean> agreements = new LinkedHashMap<>();
        agreements.put(TermAgreementType.TERMS_OF_SERVICE, termsOfService);
        agreements.put(TermAgreementType.PRIVACY_POLICY, privacyPolicy);
        agreements.put(TermAgreementType.MARKETING, marketing);
        return agreements;
    }
}
