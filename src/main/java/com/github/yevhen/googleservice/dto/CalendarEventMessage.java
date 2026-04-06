package com.github.yevhen.googleservice.dto;

import java.util.List;
import java.util.UUID;

public record CalendarEventMessage(
        UUID eventId,
        String title,
        String startDateTime,
        UUID organizerUserId,
        List<UUID> participantUserIds
) {}
