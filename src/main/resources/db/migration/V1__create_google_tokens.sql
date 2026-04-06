CREATE TABLE google_tokens (
    user_id      UUID        NOT NULL PRIMARY KEY,
    access_token TEXT        NOT NULL,
    refresh_token TEXT       NOT NULL,
    expires_at   TIMESTAMP   NOT NULL,
    created_at   TIMESTAMP   NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMP   NOT NULL DEFAULT NOW()
);
