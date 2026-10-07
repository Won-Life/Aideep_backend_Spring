-- 같은 회의 링크에 봇이 두 대 들어가지 않도록, 진행 중인 회의의 meeting_url을 유일하게 만든다.
-- 종료된(CALL_ENDED/DONE/FAILED) 회의와 삭제된 회의는 제약에서 제외해 같은 링크로 다시 초대할 수 있다.
-- meeting_url은 varchar(2048)이라 b-tree 키 최대 크기를 넘을 수 있으므로 md5 해시를 색인한다.
create unique index uq_meetings_active_url
    on aideep.meetings (md5(meeting_url))
    where deleted_at is null
        and status in ('REQUESTED'::aideep.meeting_status_enum,
                       'JOINING'::aideep.meeting_status_enum,
                       'WAITING_ROOM'::aideep.meeting_status_enum,
                       'IN_CALL_NOT_RECORDING'::aideep.meeting_status_enum,
                       'RECORDING'::aideep.meeting_status_enum);
