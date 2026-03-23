package com.github.yevhen.googleservice.controller;

import com.github.yevhen.googleservice.dto.CalendarEventRequest;
import com.github.yevhen.googleservice.dto.CalendarEventResponse;
import com.github.yevhen.googleservice.service.GoogleCalendarService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/google/calendar")
public class GoogleCalendarController {

    private final GoogleCalendarService calendarService;

    public GoogleCalendarController(GoogleCalendarService calendarService) {
        this.calendarService = calendarService;
    }

    @GetMapping("/events")
    public ResponseEntity<List<CalendarEventResponse>> listEvents(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authHeader
    ) {
        return ResponseEntity.ok(calendarService.listEvents(authHeader));
    }

    @PostMapping("/events")
    public ResponseEntity<CalendarEventResponse> createEvent(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authHeader,
            @RequestBody CalendarEventRequest request
    ) {
        return ResponseEntity.ok(calendarService.createEvent(authHeader, request));
    }

    @PutMapping("/events/{eventId}")
    public ResponseEntity<CalendarEventResponse> updateEvent(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authHeader,
            @PathVariable String eventId,
            @RequestBody CalendarEventRequest request
    ) {
        return ResponseEntity.ok(calendarService.updateEvent(authHeader, eventId, request));
    }

    @DeleteMapping("/events/{eventId}")
    public ResponseEntity<Void> deleteEvent(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authHeader,
            @PathVariable String eventId
    ) {
        calendarService.deleteEvent(authHeader, eventId);
        return ResponseEntity.noContent().build();
    }
}
