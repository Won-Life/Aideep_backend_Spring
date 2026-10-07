-- JPA `ddl-auto=validate`는 컨텍스트에 등록된 모든 엔티티를 검증하므로, 통합 테스트는 도메인과 무관하게
-- 전체 운영 스키마 계약을 사용한다. 새 엔티티를 추가하면 이 파일도 함께 갱신한다.
CREATE TYPE workspace_role_enum AS ENUM ('OWNER', 'EDITOR', 'VIEWER');
CREATE TYPE node_type_enum AS ENUM ('PROJECT', 'DATA', 'RESOURCE', 'ARCHIVE');
CREATE TYPE bot_type_enum AS ENUM ('ZOOM', 'GOOGLE', 'DISCORD');
CREATE TYPE meeting_status_enum AS ENUM
    ('REQUESTED', 'JOINING', 'WAITING_ROOM', 'IN_CALL_NOT_RECORDING', 'RECORDING', 'CALL_ENDED', 'DONE', 'FAILED');
CREATE TYPE usage_purpose_enum AS ENUM ('TEAM_PROJECT', 'SIDE_PROJECT', 'STUDY_CLUB', 'COMPANY_WORK', 'OTHER');
CREATE TYPE term_agreement_type_enum AS ENUM ('TERMS_OF_SERVICE', 'PRIVACY_POLICY', 'MARKETING');

CREATE TABLE users
(
    user_id    uuid PRIMARY KEY      DEFAULT gen_random_uuid(),
    email      varchar(255) NOT NULL UNIQUE,
    username   varchar(100),
    password   varchar(255),
    created_at timestamptz  NOT NULL DEFAULT now(),
    updated_at timestamptz  NOT NULL DEFAULT now(),
    deleted_at timestamptz
);

CREATE TABLE oauth_accounts
(
    oauth_account_id uuid PRIMARY KEY      DEFAULT gen_random_uuid(),
    user_id          uuid         NOT NULL REFERENCES users (user_id) ON DELETE CASCADE,
    provider         varchar(50)  NOT NULL,
    provider_user_id varchar(255) NOT NULL,
    email            varchar(255),
    created_at       timestamptz  NOT NULL DEFAULT now(),
    updated_at       timestamptz  NOT NULL DEFAULT now(),
    deleted_at       timestamptz,
    CONSTRAINT uq_oauth_accounts_provider_subject UNIQUE (provider, provider_user_id),
    CONSTRAINT uq_oauth_accounts_user_provider UNIQUE (user_id, provider)
);

CREATE TABLE workspaces
(
    workspace_id uuid PRIMARY KEY      DEFAULT gen_random_uuid(),
    title        varchar(255) NOT NULL DEFAULT 'Untitled',
    created_at   timestamptz  NOT NULL DEFAULT now(),
    updated_at   timestamptz  NOT NULL DEFAULT now(),
    deleted_at   timestamptz
);

CREATE TABLE users_workspaces
(
    user_id      uuid                NOT NULL REFERENCES users (user_id),
    workspace_id uuid                NOT NULL REFERENCES workspaces (workspace_id),
    role         workspace_role_enum NOT NULL DEFAULT 'EDITOR',
    joined_at    timestamptz         NOT NULL DEFAULT now(),
    deleted_at   timestamptz,
    PRIMARY KEY (user_id, workspace_id)
);

CREATE TABLE nodes
(
    node_id      uuid PRIMARY KEY        DEFAULT gen_random_uuid(),
    title        varchar(500),
    content      jsonb,
    version      integer        NOT NULL DEFAULT 1,
    node_type    node_type_enum NOT NULL DEFAULT 'DATA',
    position_x   double precision,
    position_y   double precision,
    created_at   timestamptz    NOT NULL DEFAULT now(),
    updated_at   timestamptz    NOT NULL DEFAULT now(),
    deleted_at   timestamptz,
    workspace_id uuid           NOT NULL REFERENCES workspaces (workspace_id) ON DELETE CASCADE,
    depth        integer                 DEFAULT 0
);
CREATE INDEX idx_nodes_workspace ON nodes (workspace_id);

CREATE TABLE processed_node_events
(
    event_id     uuid PRIMARY KEY,
    event_type   varchar(100) NOT NULL,
    workspace_id uuid         NOT NULL,
    occurred_at  timestamptz  NOT NULL,
    processed_at timestamptz  NOT NULL,
    created_at   timestamptz  NOT NULL DEFAULT now(),
    updated_at   timestamptz  NOT NULL DEFAULT now(),
    deleted_at   timestamptz
);
CREATE INDEX idx_processed_node_events_workspace ON processed_node_events (workspace_id);

CREATE TABLE node_command_results
(
    id uuid PRIMARY KEY,
    command_event_id uuid NOT NULL UNIQUE,
    data text NOT NULL,
    published_at timestamptz,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    deleted_at timestamptz
);

CREATE TABLE meetings
(
    meeting_id      uuid PRIMARY KEY,
    workspace_id    uuid                NOT NULL REFERENCES workspaces (workspace_id) ON DELETE CASCADE,
    node_id         uuid                NOT NULL REFERENCES nodes (node_id) ON DELETE CASCADE,
    user_id         uuid                NOT NULL REFERENCES users (user_id),
    bot_id          uuid UNIQUE,
    meeting_url     varchar(2048)       NOT NULL,
    bot_type        bot_type_enum       NOT NULL,
    status          meeting_status_enum NOT NULL DEFAULT 'REQUESTED',
    status_sub_code varchar(100),
    started_at      timestamptz,
    ended_at        timestamptz,
    last_event_at   timestamptz,
    created_at      timestamptz         NOT NULL,
    updated_at      timestamptz         NOT NULL,
    deleted_at      timestamptz
);
CREATE INDEX idx_meetings_workspace_status ON meetings (workspace_id, status);
CREATE INDEX idx_meetings_node ON meetings (node_id);
-- 진행 중인 회의의 meeting_url은 유일하다. 종료·삭제된 회의는 제외한다(V8).
CREATE UNIQUE INDEX uq_meetings_active_url
    ON meetings (md5(meeting_url))
    WHERE deleted_at IS NULL
        AND status IN ('REQUESTED', 'JOINING', 'WAITING_ROOM', 'IN_CALL_NOT_RECORDING', 'RECORDING');

CREATE TABLE user_onboarding_profiles
(
    user_onboarding_profile_id uuid PRIMARY KEY,
    user_id                    uuid          NOT NULL UNIQUE REFERENCES users (user_id) ON DELETE CASCADE,
    usage_purpose              usage_purpose_enum,
    meeting_platforms          varchar(32)[] NOT NULL DEFAULT '{}',
    completed_at               timestamptz,
    created_at                 timestamptz   NOT NULL,
    updated_at                 timestamptz   NOT NULL,
    deleted_at                 timestamptz
);

CREATE TABLE user_term_agreements
(
    user_term_agreement_id uuid PRIMARY KEY,
    user_id                uuid                     NOT NULL REFERENCES users (user_id) ON DELETE CASCADE,
    term_type              term_agreement_type_enum NOT NULL,
    agreed                 boolean                  NOT NULL DEFAULT false,
    agreed_at              timestamptz,
    revoked_at             timestamptz,
    created_at             timestamptz              NOT NULL,
    updated_at             timestamptz              NOT NULL,
    deleted_at             timestamptz,
    CONSTRAINT uq_user_term_agreements_user_term UNIQUE (user_id, term_type)
);
CREATE INDEX idx_user_term_agreements_user ON user_term_agreements (user_id);
