-- 배포 aideep 스키마에 로컬의 이벤트 처리 이력 테이블을 반영한다.
-- 로컬에서 Hibernate가 이미 생성한 테이블과 데이터는 보존한다.
-- Flyway 이력, 로컬 조회 권한, 미사용 enum/cast, 중복 OAuth UNIQUE 인덱스는 복제하지 않는다.
create table if not exists aideep.processed_node_events
(
    event_id     uuid                        not null primary key,
    created_at   timestamp(6) with time zone not null,
    deleted_at   timestamp(6) with time zone,
    updated_at   timestamp(6) with time zone not null,
    event_type   varchar(100)                not null,
    occurred_at  timestamp(6) with time zone not null,
    processed_at timestamp(6) with time zone not null,
    workspace_id uuid                        not null
);

alter table aideep.processed_node_events owner to aideep;
