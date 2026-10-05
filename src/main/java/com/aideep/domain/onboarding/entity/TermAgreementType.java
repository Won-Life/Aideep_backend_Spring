package com.aideep.domain.onboarding.entity;

import lombok.Getter;

/** 동의를 받는 약관 항목. 필수 여부는 DB가 아니라 이 enum이 계약으로 가진다. */
@Getter
public enum TermAgreementType {
    TERMS_OF_SERVICE(true),
    PRIVACY_POLICY(true),
    MARKETING(false);

    private final boolean required;

    TermAgreementType(boolean required) {
        this.required = required;
    }
}
