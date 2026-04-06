package com.github.yevhen.googleservice.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "event_participants")
public class EventParticipant {

    @Id
    private UUID id;

    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Column(name = "participant_email", nullable = false)
    private String participantEmail;

    @Column(name = "participant_user_id")
    private UUID participantUserId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected EventParticipant() {}

    public EventParticipant(UUID id, UUID eventId, String participantEmail, UUID participantUserId) {
        this.id = id;
        this.eventId = eventId;
        this.participantEmail = participantEmail;
        this.participantUserId = participantUserId;
    }

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getEventId() { return eventId; }
    public String getParticipantEmail() { return participantEmail; }
    public UUID getParticipantUserId() { return participantUserId; }
}
