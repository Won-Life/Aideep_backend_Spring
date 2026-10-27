-- 회의가 진행되는 동안 사용자가 회의 노드와 그 하위 노드를 수정하지 못하도록 잠근 회의를 기록한다.
-- null이면 수정 가능하다. AI 서버가 보내는 노드 명령은 이 값과 무관하게 계속 반영한다.
alter table aideep.nodes
    add column locked_by_meeting_id uuid
        references aideep.meetings (meeting_id)
            on delete set null;

-- 회의 종결 시 해당 회의가 잠근 노드만 찾아 해제한다.
create index idx_nodes_locked_by_meeting
    on aideep.nodes (locked_by_meeting_id)
    where locked_by_meeting_id is not null;
