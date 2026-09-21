CREATE TYPE workspace_role_enum AS ENUM ('OWNER', 'EDITOR', 'VIEWER');
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
