create table aideep.users
(
    user_id    uuid                     default gen_random_uuid() not null
        primary key,
    email      varchar(255)                                       not null
        unique,
    username   varchar(100)                                       not null,
    password   varchar(255),
    created_at timestamp with time zone default now()             not null,
    updated_at timestamp with time zone default now()             not null,
    deleted_at timestamp with time zone
);

alter table aideep.users
    owner to aideep;

create table aideep.oauth_accounts
(
    oauth_account_id uuid                        default gen_random_uuid() not null
        primary key,
    user_id          uuid                                                  not null
        references aideep.users
            on delete cascade,
    provider         varchar(50)                                           not null,
    provider_user_id varchar(255)                                          not null,
    email            varchar(255),
    created_at       timestamp(6) with time zone default CURRENT_TIMESTAMP not null,
    updated_at       timestamp(6) with time zone default CURRENT_TIMESTAMP not null,
    deleted_at       timestamp(6) with time zone
);

alter table aideep.oauth_accounts
    owner to aideep;

create index idx_oauth_accounts_user
    on aideep.oauth_accounts (user_id);

create unique index uq_oauth_accounts_provider_subject
    on aideep.oauth_accounts (provider, provider_user_id);

create unique index uq_oauth_accounts_user_provider
    on aideep.oauth_accounts (user_id, provider);

create table aideep.workspaces
(
    workspace_id uuid                     default gen_random_uuid()             not null
        primary key,
    title        varchar(255)             default 'Untitled'::character varying not null,
    created_at   timestamp with time zone default now()                         not null,
    updated_at   timestamp with time zone default now()                         not null,
    deleted_at   timestamp with time zone
);

alter table aideep.workspaces
    owner to aideep;

create table aideep.files
(
    file_id       uuid                        default gen_random_uuid()           not null
        primary key,
    file_url      varchar(2048)                                                   not null,
    s3_key        varchar(1024)                                                   not null,
    mime_type     varchar(100)                                                    not null,
    size          integer                                                         not null,
    owner_user_id uuid                                                            not null
        references aideep.users
            on delete restrict,
    workspace_id  uuid                                                            not null
        references aideep.workspaces
            on delete cascade,
    created_at    timestamp(6) with time zone default CURRENT_TIMESTAMP           not null,
    updated_at    timestamp(6) with time zone default CURRENT_TIMESTAMP           not null,
    deleted_at    timestamp(6) with time zone,
    orphaned_at   timestamp(6) with time zone,
    status        file_status_enum            default 'PENDING'::file_status_enum not null
);

alter table aideep.files
    owner to aideep;

create index idx_files_owner
    on aideep.files (owner_user_id);

create index idx_files_status
    on aideep.files (status);

create index idx_files_workspace
    on aideep.files (workspace_id);

create table aideep.nodes
(
    node_id      uuid                     default gen_random_uuid()      not null
        primary key,
    title        varchar(500),
    content      jsonb,
    version      integer                  default 1                      not null,
    node_type    node_type_enum           default 'DATA'::node_type_enum not null,
    position_x   double precision,
    position_y   double precision,
    created_at   timestamp with time zone default now()                  not null,
    updated_at   timestamp with time zone default now()                  not null,
    deleted_at   timestamp with time zone,
    workspace_id uuid                                                    not null
        references aideep.workspaces
            on delete cascade,
    depth        integer                  default 0,
    yjs_state    bytea,
    search_text  text generated always as ((((COALESCE(title, ''::character varying))::text || ' '::text) ||
                                            COALESCE((content ->> 'markdownBody'::text), ''::text))) stored
);

alter table aideep.nodes
    owner to aideep;

create table aideep.edges
(
    edge_id       uuid                     default gen_random_uuid() not null
        primary key,
    workspace_id  uuid                                               not null
        references aideep.workspaces
            on delete cascade,
    source_id     uuid                                               not null
        references aideep.nodes
            on delete cascade,
    target_id     uuid                                               not null
        references aideep.nodes
            on delete cascade,
    source_handle varchar(250),
    target_handle varchar(250),
    version       integer                  default 1                 not null,
    created_at    timestamp with time zone default now()             not null,
    updated_at    timestamp with time zone default now()             not null,
    deleted_at    timestamp with time zone
);

alter table aideep.edges
    owner to aideep;

create index idx_edges_source
    on aideep.edges (source_id);

create index idx_edges_target
    on aideep.edges (target_id);

create index idx_edges_workspace
    on aideep.edges (workspace_id);

create index idx_edges_workspace_updated
    on aideep.edges (workspace_id, updated_at);

create table aideep.file_attachments
(
    file_id    uuid                                                  not null
        references aideep.files
            on delete cascade,
    node_id    uuid                                                  not null
        references aideep.nodes
            on delete cascade,
    created_at timestamp(6) with time zone default CURRENT_TIMESTAMP not null,
    primary key (file_id, node_id)
);

alter table aideep.file_attachments
    owner to aideep;

create index idx_file_attachments_file
    on aideep.file_attachments (file_id);

create index idx_file_attachments_node
    on aideep.file_attachments (node_id);

create index idx_nodes_search_trgm
    on aideep.nodes using gin (search_text gin_trgm_ops)
    where (deleted_at IS NULL);

create index idx_nodes_workspace
    on aideep.nodes (workspace_id);

create index idx_nodes_workspace_updated
    on aideep.nodes (workspace_id, updated_at);

create table aideep.users_workspaces
(
    user_id      uuid                                                           not null
        references aideep.users
            on delete cascade,
    workspace_id uuid                                                           not null
        references aideep.workspaces
            on delete cascade,
    role         workspace_role_enum      default 'EDITOR'::workspace_role_enum not null,
    joined_at    timestamp with time zone default now()                         not null,
    deleted_at   timestamp with time zone,
    primary key (user_id, workspace_id)
);

alter table aideep.users_workspaces
    owner to aideep;
