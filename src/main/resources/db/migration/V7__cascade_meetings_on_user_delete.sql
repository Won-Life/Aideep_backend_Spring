-- 계정 하드 삭제 시 회의 기록이 FK restrict로 삭제를 막지 않도록 cascade로 바꾼다.
alter table aideep.meetings
    drop constraint meetings_user_id_fkey;

alter table aideep.meetings
    add constraint meetings_user_id_fkey foreign key (user_id)
        references aideep.users (user_id) on delete cascade;
