package com.aideep.domain.meeting.repository;

import com.aideep.domain.meeting.entity.Meeting;
import com.aideep.domain.meeting.entity.MeetingStatus;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MeetingRepository extends JpaRepository<Meeting, UUID> {

    Optional<Meeting> findByBotIdAndDeletedAtIsNull(UUID botId);

    List<Meeting> findByWorkspaceIdAndStatusInAndDeletedAtIsNull(UUID workspaceId,
                                                                 Collection<MeetingStatus> statuses);

    List<Meeting> findByWorkspaceIdAndDeletedAtIsNullOrderByCreatedAtDesc(UUID workspaceId);
}
