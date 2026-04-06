package com.github.yevhen.googleservice.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "calendar_events")
public class CalendarEvent {

    @Id
    private UUID id;

    @Column(name = "organizer_user_id", nullable = false)
    private UUID organizerUserId;

    @Column(nullable = false)
    private String title;

    @Column(name = "start_date_time", nullable = false)
    private OffsetDateTime startDateTime;

    @Column(name = "end_date_time", nullable = false)
    private OffsetDateTime endDateTime;

    @Column(nullable = false)
    private boolean deleted;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected CalendarEvent() {}

    public CalendarEvent(UUID id, UUID organizerUserId, String title, OffsetDateTime startDateTime, OffsetDateTime endDateTime) {
        this.id = id;
        this.organizerUserId = organizerUserId;
        this.title = title;
        this.startDateTime = startDateTime;
        this.endDateTime = endDateTime;
        this.deleted = false;
    }

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
        updatedAt = Instant.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getOrganizerUserId() { return organizerUserId; }
    public String getTitle() { return title; }
    public OffsetDateTime getStartDateTime() { return startDateTime; }
    public OffsetDateTime getEndDateTime() { return endDateTime; }
    public boolean isDeleted() { return deleted; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void setTitle(String title) { this.title = title; }
    public void setStartDateTime(OffsetDateTime startDateTime) { this.startDateTime = startDateTime; }
    public void setEndDateTime(OffsetDateTime endDateTime) { this.endDateTime = endDateTime; }
    public void setDeleted(boolean deleted) { this.deleted = deleted; }
}
