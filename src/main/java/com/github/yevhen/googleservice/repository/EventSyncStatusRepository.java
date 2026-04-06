package com.github.yevhen.googleservice.repository;

import com.github.yevhen.googleservice.model.EventSyncStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface EventSyncStatusRepository extends JpaRepository<EventSyncStatus, UUID> {
    Optional<EventSyncStatus> findByEventIdAndParticipantUserId(UUID eventId, UUID participantUserId);
    List<EventSyncStatus> findByEventId(UUID eventId);
    List<EventSyncStatus> findByParticipantUserId(UUID participantUserId);
}
