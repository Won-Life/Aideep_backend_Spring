package com.aideep.domain.meeting.repository;

import com.aideep.domain.meeting.entity.Meeting;
import com.aideep.domain.meeting.entity.MeetingStatus;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MeetingRepository extends JpaRepository<Meeting, UUID> {

    Optional<Meeting> findByBotIdAndDeletedAtIsNull(UUID botId);

    List<Meeting> findByWorkspaceIdAndStatusInAndDeletedAtIsNull(UUID workspaceId,
                                                                 Collection<MeetingStatus> statuses);

    List<Meeting> findByWorkspaceIdAndDeletedAtIsNullOrderByCreatedAtDesc(UUID workspaceId);

    boolean existsByMeetingUrlAndStatusInAndDeletedAtIsNull(String meetingUrl, Collection<MeetingStatus> statuses);

    /**
     * 과금 구간이 기간 {@code [from, to)} 와 겹치는 회의. 아직 끝나지 않은 회의도 포함한다.
     */
    @Query("""
            select m from Meeting m
            where m.deletedAt is null
              and m.createdAt < :to
              and (m.endedAt is null or m.endedAt > :from)
            order by m.createdAt
            """)
    List<Meeting> findOverlapping(@Param("from") Instant from, @Param("to") Instant to);
}
