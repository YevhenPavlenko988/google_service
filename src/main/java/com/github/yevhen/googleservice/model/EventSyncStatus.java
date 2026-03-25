package com.github.yevhen.googleservice.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "event_sync_status")
public class EventSyncStatus {

    public enum SyncStatus {
        PENDING,
        SYNCED,
        FAILED,
        NOT_CONNECTED
    }

    @Id
    private UUID id;

    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Column(name = "participant_user_id", nullable = false)
    private UUID participantUserId;

    @Column(name = "google_event_id")
    private String googleEventId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SyncStatus status;

    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected EventSyncStatus() {}

    public EventSyncStatus(UUID id, UUID eventId, UUID participantUserId, SyncStatus status) {
        this.id = id;
        this.eventId = eventId;
        this.participantUserId = participantUserId;
        this.status = status;
    }

    @PrePersist
    protected void onCreate() {
        updatedAt = Instant.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getEventId() { return eventId; }
    public UUID getParticipantUserId() { return participantUserId; }
    public String getGoogleEventId() { return googleEventId; }
    public SyncStatus getStatus() { return status; }
    public String getLastError() { return lastError; }

    public void setGoogleEventId(String googleEventId) { this.googleEventId = googleEventId; }
    public void setStatus(SyncStatus status) { this.status = status; }
    public void setLastError(String lastError) { this.lastError = lastError; }
}
