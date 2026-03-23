package com.github.yevhen.googleservice.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.github.yevhen.common.exception.ServiceException;
import com.github.yevhen.common.security.JwtHelper;
import com.github.yevhen.googleservice.config.GoogleProperties;
import com.github.yevhen.googleservice.dto.CalendarEventRequest;
import com.github.yevhen.googleservice.dto.CalendarEventResponse;
import com.github.yevhen.googleservice.dto.GoogleTokenResponse;
import com.github.yevhen.googleservice.model.GoogleToken;
import com.github.yevhen.googleservice.repository.GoogleTokenRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class GoogleCalendarService {

    private static final Logger log = LoggerFactory.getLogger(GoogleCalendarService.class);

    private final JwtHelper jwtHelper;
    private final GoogleProperties googleProps;
    private final GoogleTokenRepository googleTokenRepository;
    private final GoogleAuthService googleAuthService;
    private final RestClient restClient;

    public GoogleCalendarService(
            JwtHelper jwtHelper,
            GoogleProperties googleProps,
            GoogleTokenRepository googleTokenRepository,
            GoogleAuthService googleAuthService,
            RestClient restClient
    ) {
        this.jwtHelper = jwtHelper;
        this.googleProps = googleProps;
        this.googleTokenRepository = googleTokenRepository;
        this.googleAuthService = googleAuthService;
        this.restClient = restClient;
    }

    public List<CalendarEventResponse> listEvents(String authorizationHeader) {
        String accessToken = getValidAccessToken(authorizationHeader);

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        String timeMin = now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        String timeMax = now.plusDays(7).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);

        try {
            GoogleEventsListResponse response = restClient.get()
                    .uri(googleProps.getCalendarBaseUrl()
                            + "/calendars/primary/events"
                            + "?timeMin=" + timeMin
                            + "&timeMax=" + timeMax
                            + "&singleEvents=true"
                            + "&orderBy=startTime")
                    .header("Authorization", "Bearer " + accessToken)
                    .retrieve()
                    .body(GoogleEventsListResponse.class);

            if (response == null || response.items() == null) return List.of();
            return response.items().stream().map(this::mapEvent).toList();
        } catch (RestClientResponseException e) {
            log.error("Google Calendar list events failed: {}", e.getResponseBodyAsString());
            throw new ServiceException("Failed to list calendar events", HttpStatus.BAD_GATEWAY);
        }
    }

    public CalendarEventResponse createEvent(String authorizationHeader, CalendarEventRequest request) {
        String accessToken = getValidAccessToken(authorizationHeader);

        try {
            GoogleEventItem created = restClient.post()
                    .uri(googleProps.getCalendarBaseUrl()
                            + "/calendars/primary/events?sendUpdates=all&conferenceDataVersion=1")
                    .header("Authorization", "Bearer " + accessToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(buildEventBody(request, true))
                    .retrieve()
                    .body(GoogleEventItem.class);

            return mapEvent(created);
        } catch (RestClientResponseException e) {
            log.error("Google Calendar create event failed: {}", e.getResponseBodyAsString());
            throw new ServiceException("Failed to create calendar event", HttpStatus.BAD_GATEWAY);
        }
    }

    public CalendarEventResponse updateEvent(String authorizationHeader, String eventId, CalendarEventRequest request) {
        String accessToken = getValidAccessToken(authorizationHeader);

        try {
            GoogleEventItem updated = restClient.put()
                    .uri(googleProps.getCalendarBaseUrl()
                            + "/calendars/primary/events/" + eventId
                            + "?sendUpdates=all&conferenceDataVersion=1")
                    .header("Authorization", "Bearer " + accessToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(buildEventBody(request, false))
                    .retrieve()
                    .body(GoogleEventItem.class);

            return mapEvent(updated);
        } catch (RestClientResponseException e) {
            log.error("Google Calendar update event failed: {}", e.getResponseBodyAsString());
            throw new ServiceException("Failed to update calendar event", HttpStatus.BAD_GATEWAY);
        }
    }

    public void deleteEvent(String authorizationHeader, String eventId) {
        String accessToken = getValidAccessToken(authorizationHeader);

        try {
            restClient.delete()
                    .uri(googleProps.getCalendarBaseUrl() + "/calendars/primary/events/" + eventId)
                    .header("Authorization", "Bearer " + accessToken)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException e) {
            log.error("Google Calendar delete event failed: {}", e.getResponseBodyAsString());
            throw new ServiceException("Failed to delete calendar event", HttpStatus.BAD_GATEWAY);
        }
    }

    // ─── Token helpers ────────────────────────────────────────────────────────

    @Transactional
    String getValidAccessToken(String authorizationHeader) {
        UUID userId = jwtHelper.extractCallerInfo(authorizationHeader).id();

        GoogleToken token = googleTokenRepository.findByUserId(userId)
                .orElseThrow(() -> new ServiceException(
                        "Google Calendar is not connected. Please connect your calendar first.",
                        HttpStatus.PRECONDITION_REQUIRED));

        if (token.getExpiresAt().isBefore(Instant.now().plusSeconds(60))) {
            log.info("Google access token expired for userId={}, refreshing...", userId);
            GoogleTokenResponse refreshed = googleAuthService.refreshAccessToken(token.getRefreshToken());
            token.setAccessToken(refreshed.accessToken());
            token.setExpiresAt(Instant.now().plusSeconds(refreshed.expiresIn() - 30));
            googleTokenRepository.save(token);
        }

        return token.getAccessToken();
    }

    // ─── Mapping helpers ──────────────────────────────────────────────────────

    private Map<String, Object> buildEventBody(CalendarEventRequest request, boolean createMeet) {
        Map<String, Object> body = new HashMap<>();
        body.put("summary", request.title());
        body.put("start", Map.of("dateTime", request.startDateTime()));
        body.put("end", Map.of("dateTime", request.endDateTime()));

        if (request.attendeeEmails() != null && !request.attendeeEmails().isEmpty()) {
            body.put("attendees", request.attendeeEmails().stream()
                    .map(email -> Map.of("email", email))
                    .toList());
        }

        if (createMeet) {
            body.put("conferenceData", Map.of(
                    "createRequest", Map.of(
                            "requestId", UUID.randomUUID().toString(),
                            "conferenceSolutionKey", Map.of("type", "hangoutsMeet")
                    )
            ));
        }

        return body;
    }

    private CalendarEventResponse mapEvent(GoogleEventItem item) {
        String start = item.start() != null ? item.start().dateTime() : null;
        String end   = item.end()   != null ? item.end().dateTime()   : null;

        String meetLink = null;
        if (item.conferenceData() != null && item.conferenceData().entryPoints() != null) {
            meetLink = item.conferenceData().entryPoints().stream()
                    .filter(ep -> "video".equals(ep.entryPointType()))
                    .map(GoogleEntryPoint::uri)
                    .findFirst()
                    .orElse(null);
        }

        List<String> attendeeEmails = item.attendees() != null
                ? item.attendees().stream().map(GoogleAttendee::email).toList()
                : List.of();

        return new CalendarEventResponse(item.id(), item.summary(), start, end, item.htmlLink(), meetLink, attendeeEmails);
    }

    // ─── Google API response records ──────────────────────────────────────────

    @JsonIgnoreProperties(ignoreUnknown = true)
    record GoogleEventsListResponse(List<GoogleEventItem> items) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record GoogleEventItem(
            String id,
            String summary,
            @JsonProperty("htmlLink") String htmlLink,
            GoogleDateTime start,
            GoogleDateTime end,
            List<GoogleAttendee> attendees,
            @JsonProperty("conferenceData") GoogleConferenceData conferenceData
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record GoogleConferenceData(
            @JsonProperty("entryPoints") List<GoogleEntryPoint> entryPoints
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record GoogleEntryPoint(
            @JsonProperty("entryPointType") String entryPointType,
            @JsonProperty("uri") String uri
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record GoogleDateTime(
            @JsonProperty("dateTime") String dateTime,
            @JsonProperty("date") String date
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record GoogleAttendee(
            String email,
            @JsonProperty("responseStatus") String responseStatus,
            @JsonProperty("self") Boolean self
    ) {}
}
