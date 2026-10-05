create table aideep.node_command_results
(
    id uuid primary key,
    command_event_id uuid not null unique,
    data text not null,
    published_at timestamp(6) with time zone,
    created_at timestamp(6) with time zone not null,
    updated_at timestamp(6) with time zone not null,
    deleted_at timestamp(6) with time zone
);
alter table aideep.node_command_results owner to aideep;
