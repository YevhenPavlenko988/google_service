package com.github.yevhen.googleservice.dto;

import java.util.List;

public record CalendarEventResponse(
        String id,
        String title,
        String startDateTime,
        String endDateTime,
        String htmlLink,
        String meetLink,
        List<String> attendeeEmails,
        String jitsiRoomName
) {}
