CREATE TABLE calendar_events (
    id                UUID PRIMARY KEY,
    organizer_user_id UUID        NOT NULL,
    title             VARCHAR(255) NOT NULL,
    start_date_time   TIMESTAMP    NOT NULL,
    end_date_time     TIMESTAMP    NOT NULL,
    deleted           BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at        TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE TABLE event_participants (
    id                  UUID PRIMARY KEY,
    event_id            UUID         NOT NULL REFERENCES calendar_events(id) ON DELETE CASCADE,
    participant_email   VARCHAR(255) NOT NULL,
    participant_user_id UUID,
    created_at          TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX ux_event_participants_event_email
    ON event_participants(event_id, participant_email);

CREATE INDEX idx_event_participants_email
    ON event_participants(participant_email);

CREATE INDEX idx_event_participants_user_id
    ON event_participants(participant_user_id);

CREATE TABLE event_sync_status (
    id                  UUID PRIMARY KEY,
    event_id            UUID         NOT NULL REFERENCES calendar_events(id) ON DELETE CASCADE,
    participant_user_id UUID         NOT NULL,
    google_event_id     VARCHAR(255),
    status              VARCHAR(32)  NOT NULL,
    last_error          TEXT,
    updated_at          TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX ux_event_sync_event_user
    ON event_sync_status(event_id, participant_user_id);

CREATE INDEX idx_event_sync_user_status
    ON event_sync_status(participant_user_id, status);
