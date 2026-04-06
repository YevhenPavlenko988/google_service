package com.github.yevhen.googleservice.repository;

import com.github.yevhen.googleservice.model.EventParticipant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface EventParticipantRepository extends JpaRepository<EventParticipant, UUID> {
    List<EventParticipant> findByEventId(UUID eventId);
    void deleteByEventId(UUID eventId);
    List<EventParticipant> findByParticipantEmailIgnoreCase(String participantEmail);
}
