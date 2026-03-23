package com.github.yevhen.googleservice.dto;

import java.util.List;

public record CalendarEventRequest(
        String title,
        String startDateTime,
        String endDateTime,
        List<String> attendeeEmails
) {}
