-- JPA `ddl-auto=validate`는 컨텍스트에 등록된 모든 엔티티를 검증하므로, 통합 테스트는 도메인과 무관하게
-- 전체 운영 스키마 계약을 사용한다. 새 엔티티를 추가하면 이 파일도 함께 갱신한다.
CREATE TYPE workspace_role_enum AS ENUM ('OWNER', 'EDITOR', 'VIEWER');
CREATE TYPE node_type_enum AS ENUM ('PROJECT', 'DATA', 'RESOURCE', 'ARCHIVE');

CREATE TABLE users
(
    user_id    uuid PRIMARY KEY      DEFAULT gen_random_uuid(),
    email      varchar(255) NOT NULL UNIQUE,
    username   varchar(100) NOT NULL,
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
