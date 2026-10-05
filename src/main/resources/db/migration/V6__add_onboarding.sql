-- 온보딩 설문 답변과 약관 항목별 동의 상태를 보관한다.
create type aideep.usage_purpose_enum as enum
    ('TEAM_PROJECT', 'SIDE_PROJECT', 'STUDY_CLUB', 'COMPANY_WORK', 'OTHER');

create type aideep.term_agreement_type_enum as enum
    ('TERMS_OF_SERVICE', 'PRIVACY_POLICY', 'MARKETING');

create table aideep.user_onboarding_profiles
(
    user_onboarding_profile_id uuid                        not null primary key,
    user_id                    uuid                        not null
        unique
        references aideep.users
            on delete cascade,
    usage_purpose              aideep.usage_purpose_enum,
    meeting_platforms          varchar(32)[]               not null default '{}',
    completed_at               timestamp(6) with time zone,
    created_at                 timestamp(6) with time zone not null,
    updated_at                 timestamp(6) with time zone not null,
    deleted_at                 timestamp(6) with time zone
);

alter table aideep.user_onboarding_profiles
    owner to aideep;

create table aideep.user_term_agreements
(
    user_term_agreement_id uuid                             not null primary key,
    user_id                uuid                             not null
        references aideep.users
            on delete cascade,
    term_type              aideep.term_agreement_type_enum  not null,
    agreed                 boolean                          not null default false,
    agreed_at              timestamp(6) with time zone,
    revoked_at             timestamp(6) with time zone,
    created_at             timestamp(6) with time zone      not null,
    updated_at             timestamp(6) with time zone      not null,
    deleted_at             timestamp(6) with time zone,
    constraint uq_user_term_agreements_user_term unique (user_id, term_type)
);

alter table aideep.user_term_agreements
    owner to aideep;

create index idx_user_term_agreements_user
    on aideep.user_term_agreements (user_id);
