-- 봇을 초대한 회의와 Recall 봇 생명주기 상태를 관리한다.
create type aideep.bot_type_enum as enum ('ZOOM', 'GOOGLE', 'DISCORD');

create type aideep.meeting_status_enum as enum
    ('REQUESTED', 'JOINING', 'WAITING_ROOM', 'IN_CALL_NOT_RECORDING', 'RECORDING', 'CALL_ENDED', 'DONE', 'FAILED');

create table aideep.meetings
(
    meeting_id      uuid                        not null primary key,
    workspace_id    uuid                        not null
        references aideep.workspaces
            on delete cascade,
    node_id         uuid                        not null
        references aideep.nodes
            on delete cascade,
    user_id         uuid                        not null
        references aideep.users
            on delete restrict,
    bot_id          uuid
        unique,
    meeting_url     varchar(2048)               not null,
    bot_type        aideep.bot_type_enum        not null,
    status          aideep.meeting_status_enum  not null default 'REQUESTED',
    status_sub_code varchar(100),
    started_at      timestamp(6) with time zone,
    ended_at        timestamp(6) with time zone,
    last_event_at   timestamp(6) with time zone,
    created_at      timestamp(6) with time zone not null,
    updated_at      timestamp(6) with time zone not null,
    deleted_at      timestamp(6) with time zone
);

alter table aideep.meetings
    owner to aideep;

create index idx_meetings_workspace_status
    on aideep.meetings (workspace_id, status);

create index idx_meetings_workspace_created
    on aideep.meetings (workspace_id, created_at desc);

create index idx_meetings_node
    on aideep.meetings (node_id);
