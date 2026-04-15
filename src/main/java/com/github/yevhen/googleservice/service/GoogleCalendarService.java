package com.github.yevhen.googleservice.service;

import com.github.yevhen.common.exception.ServiceException;
import com.github.yevhen.common.security.CallerInfo;
import com.github.yevhen.common.security.JwtHelper;
import com.github.yevhen.googleservice.config.GoogleProperties;
import com.github.yevhen.googleservice.dto.CalendarEventRequest;
import com.github.yevhen.googleservice.dto.CalendarEventResponse;
import com.github.yevhen.googleservice.dto.InternalResolveUsersRequest;
import com.github.yevhen.googleservice.dto.InternalResolvedUserResponse;
import com.github.yevhen.googleservice.dto.GoogleTokenResponse;
import com.github.yevhen.googleservice.event.SyncCalendarEvent;
import com.github.yevhen.googleservice.event.DeleteCalendarEvent;
import com.github.yevhen.googleservice.model.CalendarEvent;
import com.github.yevhen.googleservice.model.EventParticipant;
import com.github.yevhen.googleservice.model.EventSyncStatus;
import com.github.yevhen.googleservice.model.GoogleToken;
import com.github.yevhen.googleservice.repository.CalendarEventRepository;
import com.github.yevhen.googleservice.repository.EventParticipantRepository;
import com.github.yevhen.googleservice.repository.EventSyncStatusRepository;
import com.github.yevhen.googleservice.repository.GoogleTokenRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.github.yevhen.googleservice.config.RabbitConfig;
import com.github.yevhen.googleservice.dto.CalendarEventMessage;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class GoogleCalendarService {

    private static final Logger log = LoggerFactory.getLogger(GoogleCalendarService.class);

    private final JwtHelper jwtHelper;
    private final GoogleProperties googleProps;
    private final GoogleTokenRepository googleTokenRepository;
    private final GoogleAuthService googleAuthService;
    private final CalendarEventRepository calendarEventRepository;
    private final EventParticipantRepository participantRepository;
    private final EventSyncStatusRepository syncStatusRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final RabbitTemplate rabbitTemplate;
    private final RestClient restClient;
    private final String authServiceUrl;
    private final String jitsiServiceUrl;

    public GoogleCalendarService(
            JwtHelper jwtHelper,
            GoogleProperties googleProps,
            GoogleTokenRepository googleTokenRepository,
            GoogleAuthService googleAuthService,
            CalendarEventRepository calendarEventRepository,
            EventParticipantRepository participantRepository,
            EventSyncStatusRepository syncStatusRepository,
            ApplicationEventPublisher eventPublisher,
            RabbitTemplate rabbitTemplate,
            RestClient restClient,
            @org.springframework.beans.factory.annotation.Value("${app.auth-service-url}") String authServiceUrl,
            @org.springframework.beans.factory.annotation.Value("${app.jitsi-service-url:http://jitsi-meet:8083}") String jitsiServiceUrl
    ) {
        this.jwtHelper = jwtHelper;
        this.googleProps = googleProps;
        this.googleTokenRepository = googleTokenRepository;
        this.googleAuthService = googleAuthService;
        this.calendarEventRepository = calendarEventRepository;
        this.participantRepository = participantRepository;
        this.syncStatusRepository = syncStatusRepository;
        this.eventPublisher = eventPublisher;
        this.rabbitTemplate = rabbitTemplate;
        this.restClient = restClient;
        this.authServiceUrl = authServiceUrl;
        this.jitsiServiceUrl = jitsiServiceUrl;
    }

    public List<CalendarEventResponse> listEvents(String authorizationHeader) {
        CallerInfo caller = jwtHelper.extractCallerInfo(authorizationHeader);
        OffsetDateTime from = OffsetDateTime.now(ZoneOffset.UTC).withHour(0).withMinute(0).withSecond(0).withNano(0);
        OffsetDateTime max = from.plusDays(7);
        List<EventParticipant> myParticipants = participantRepository.findByParticipantEmailIgnoreCase(normalizeEmail(caller.email()));
        List<UUID> eventIds = myParticipants.stream()
                .map(EventParticipant::getEventId)
                .distinct()
                .toList();
        if (eventIds.isEmpty()) {
            return List.of();
        }

        List<CalendarEvent> events = calendarEventRepository.findByIdInAndDeletedFalseAndStartDateTimeBetween(eventIds, from, max);
        Map<UUID, List<EventParticipant>> participantsByEvent = participantRepository.findAll().stream()
                .filter(p -> eventIds.contains(p.getEventId()))
                .collect(Collectors.groupingBy(EventParticipant::getEventId));
        Map<UUID, EventSyncStatus> mySyncMap = syncStatusRepository.findByParticipantUserId(caller.id()).stream()
                .collect(Collectors.toMap(EventSyncStatus::getEventId, s -> s, (a, b) -> a));

        return events.stream()
                .map(event -> renderEventForCaller(event, participantsByEvent.getOrDefault(event.getId(), List.of()), mySyncMap.get(event.getId()), caller.id()))
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(CalendarEventResponse::startDateTime, Comparator.nullsLast(String::compareTo)))
                .toList();
    }

    @Transactional
    public CalendarEventResponse createEvent(String authorizationHeader, CalendarEventRequest request) {
        CallerInfo caller = jwtHelper.extractCallerInfo(authorizationHeader);
        validateRequest(request);

        CalendarEvent event = new CalendarEvent(
                UUID.randomUUID(),
                caller.id(),
                request.title().trim(),
                OffsetDateTime.parse(request.startDateTime()),
                OffsetDateTime.parse(request.endDateTime())
        );
        calendarEventRepository.save(event);

        List<String> attendeeEmails = collectParticipantEmails(caller.email(), request.attendeeEmails());
        Map<String, UUID> resolvedUsers = resolveUsersByEmails(attendeeEmails);
        participantRepository.saveAll(attendeeEmails.stream()
                .map(email -> new EventParticipant(UUID.randomUUID(), event.getId(), email, resolvedUsers.get(email)))
                .toList());

        preCreateJitsiRoom(event, caller.id(), attendeeEmails);

        syncStatusRepository.saveAll(resolvedUsers.values().stream()
                .map(userId -> new EventSyncStatus(UUID.randomUUID(), event.getId(), userId, EventSyncStatus.SyncStatus.PENDING))
                .toList());
        eventPublisher.publishEvent(new SyncCalendarEvent(event.getId()));

        // Notify invited participants via push notification service
        List<UUID> participantIds = new java.util.ArrayList<>(resolvedUsers.values());
        rabbitTemplate.convertAndSend(
                RabbitConfig.EXCHANGE,
                RabbitConfig.CALENDAR_KEY,
                new CalendarEventMessage(
                        event.getId(),
                        event.getTitle(),
                        event.getStartDateTime().toString(),
                        caller.id(),
                        participantIds
                )
        );

        return mapLocalEvent(event, attendeeEmails);
    }

    @Transactional
    public CalendarEventResponse updateEvent(String authorizationHeader, String eventId, CalendarEventRequest request) {
        CallerInfo caller = jwtHelper.extractCallerInfo(authorizationHeader);
        validateRequest(request);
        UUID localEventId = parseEventId(eventId);

        CalendarEvent event = calendarEventRepository.findById(localEventId)
                .orElseThrow(() -> new ServiceException("Event not found", HttpStatus.NOT_FOUND));
        if (!event.getOrganizerUserId().equals(caller.id())) {
            throw new ServiceException("Only organizer can edit this event", HttpStatus.FORBIDDEN);
        }

        event.setTitle(request.title().trim());
        event.setStartDateTime(OffsetDateTime.parse(request.startDateTime()));
        event.setEndDateTime(OffsetDateTime.parse(request.endDateTime()));
        calendarEventRepository.save(event);

        participantRepository.deleteByEventId(event.getId());
        List<String> attendeeEmails = collectParticipantEmails(caller.email(), request.attendeeEmails());
        Map<String, UUID> resolvedUsers = resolveUsersByEmails(attendeeEmails);
        participantRepository.saveAll(attendeeEmails.stream()
                .map(email -> new EventParticipant(UUID.randomUUID(), event.getId(), email, resolvedUsers.get(email)))
                .toList());

        List<EventSyncStatus> existingStatuses = syncStatusRepository.findByEventId(event.getId());
        for (EventSyncStatus status : existingStatuses) {
            if (resolvedUsers.containsValue(status.getParticipantUserId())) {
                status.setStatus(EventSyncStatus.SyncStatus.PENDING);
                status.setLastError(null);
                syncStatusRepository.save(status);
            }
        }
        for (UUID userId : resolvedUsers.values()) {
            syncStatusRepository.findByEventIdAndParticipantUserId(event.getId(), userId)
                    .orElseGet(() -> syncStatusRepository.save(
                            new EventSyncStatus(UUID.randomUUID(), event.getId(), userId, EventSyncStatus.SyncStatus.PENDING)
                    ));
        }

        eventPublisher.publishEvent(new SyncCalendarEvent(event.getId()));
        return mapLocalEvent(event, attendeeEmails);
    }

    @Transactional
    public void deleteEvent(String authorizationHeader, String eventId) {
        CallerInfo caller = jwtHelper.extractCallerInfo(authorizationHeader);
        UUID localEventId = parseEventId(eventId);
        CalendarEvent event = calendarEventRepository.findById(localEventId)
                .orElseThrow(() -> new ServiceException("Event not found", HttpStatus.NOT_FOUND));
        if (!event.getOrganizerUserId().equals(caller.id())) {
            throw new ServiceException("Only organizer can delete this event", HttpStatus.FORBIDDEN);
        }
        event.setDeleted(true);
        calendarEventRepository.save(event);
        eventPublisher.publishEvent(new DeleteCalendarEvent(localEventId));
    }

    private CalendarEventResponse renderEventForCaller(
            CalendarEvent event,
            List<EventParticipant> participants,
            EventSyncStatus sync,
            UUID callerUserId
    ) {
        List<String> attendeeEmails = participants.stream().map(EventParticipant::getParticipantEmail).toList();
        if (sync != null
                && sync.getStatus() == EventSyncStatus.SyncStatus.SYNCED
                && sync.getGoogleEventId() != null
                && !sync.getGoogleEventId().isBlank()) {
            try {
                String accessToken = getValidAccessTokenByUserId(callerUserId);
                GoogleEventItem googleEvent = restClient.get()
                        .uri(googleProps.getCalendarBaseUrl() + "/calendars/primary/events/" + sync.getGoogleEventId())
                        .header("Authorization", "Bearer " + accessToken)
                        .retrieve()
                        .body(GoogleEventItem.class);
                if (googleEvent != null) {
                    return mapGoogleEventWithLocalId(event.getId(), googleEvent, attendeeEmails, event.getJitsiRoomName());
                }
            } catch (Exception e) {
                log.debug("Falling back to local event view for eventId={} userId={}", event.getId(), callerUserId);
            }
        }
        return mapLocalEvent(event, attendeeEmails);
    }

    private CalendarEventResponse mapGoogleEventWithLocalId(UUID localId, GoogleEventItem item, List<String> attendeeEmails, String jitsiRoomName) {
        String start = item.start() != null ? item.start().dateTime() : null;
        String end = item.end() != null ? item.end().dateTime() : null;
        String meetLink = null;
        if (item.conferenceData() != null && item.conferenceData().entryPoints() != null) {
            meetLink = item.conferenceData().entryPoints().stream()
                    .filter(ep -> "video".equals(ep.entryPointType()))
                    .map(GoogleEntryPoint::uri)
                    .findFirst()
                    .orElse(null);
        }
        return new CalendarEventResponse(
                localId.toString(),
                item.summary(),
                start,
                end,
                item.htmlLink(),
                meetLink,
                attendeeEmails,
                jitsiRoomName
        );
    }

    private CalendarEventResponse mapLocalEvent(CalendarEvent event, List<String> attendeeEmails) {
        return new CalendarEventResponse(
                event.getId().toString(),
                event.getTitle(),
                event.getStartDateTime().toString(),
                event.getEndDateTime().toString(),
                null,
                null,
                attendeeEmails,
                event.getJitsiRoomName()
        );
    }

    private List<String> collectParticipantEmails(String organizerEmail, List<String> attendeeEmails) {
        LinkedHashSet<String> emails = new LinkedHashSet<>();
        emails.add(normalizeEmail(organizerEmail));
        if (attendeeEmails != null) {
            attendeeEmails.stream()
                    .filter(Objects::nonNull)
                    .map(this::normalizeEmail)
                    .filter(s -> !s.isBlank())
                    .forEach(emails::add);
        }
        return emails.stream().toList();
    }

    private String normalizeEmail(String value) {
        return value == null ? "" : value.trim().toLowerCase();
    }

    private Map<String, UUID> resolveUsersByEmails(List<String> emails) {
        try {
            List<InternalResolvedUserResponse> resolved = restClient.post()
                    .uri(authServiceUrl + "/internal/users/resolve-emails")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new InternalResolveUsersRequest(emails))
                    .retrieve()
                    .body(new org.springframework.core.ParameterizedTypeReference<>() {});

            if (resolved == null) {
                return Map.of();
            }
            return resolved.stream().collect(Collectors.toMap(
                    item -> normalizeEmail(item.email()),
                    InternalResolvedUserResponse::id,
                    (a, b) -> a
            ));
        } catch (Exception e) {
            log.warn("Failed to resolve users by email in auth service: {}", e.getMessage());
            return Map.of();
        }
    }

    private void preCreateJitsiRoom(CalendarEvent event, UUID organizerId, List<String> participantEmails) {
        try {
            Map<?, ?> response = restClient.post()
                    .uri(jitsiServiceUrl + "/jitsi/internal/rooms")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of(
                            "createdByUserId", organizerId.toString(),
                            "eventRef", event.getId().toString(),
                            "participantEmails", participantEmails
                    ))
                    .retrieve()
                    .body(Map.class);
            if (response != null && response.get("roomName") instanceof String roomName) {
                event.setJitsiRoomName(roomName);
                calendarEventRepository.save(event);
                log.info("Pre-created Jitsi room={} for calendarEvent={}", roomName, event.getId());
            }
        } catch (Exception e) {
            log.warn("Could not pre-create Jitsi room for calendarEvent={}: {}", event.getId(), e.getMessage());
        }
    }

    private void validateRequest(CalendarEventRequest request) {
        if (request == null || request.title() == null || request.title().isBlank()) {
            throw new ServiceException("Title is required", HttpStatus.BAD_REQUEST);
        }
        if (request.startDateTime() == null || request.endDateTime() == null) {
            throw new ServiceException("Start/end date time are required", HttpStatus.BAD_REQUEST);
        }
        try {
            OffsetDateTime start = OffsetDateTime.parse(request.startDateTime());
            OffsetDateTime end = OffsetDateTime.parse(request.endDateTime());
            if (!end.isAfter(start)) {
                throw new ServiceException("End time must be after start time", HttpStatus.BAD_REQUEST);
            }
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            throw new ServiceException("Invalid date-time format", HttpStatus.BAD_REQUEST);
        }
    }

    private UUID parseEventId(String eventId) {
        try {
            return UUID.fromString(eventId);
        } catch (Exception e) {
            throw new ServiceException("Invalid event id", HttpStatus.BAD_REQUEST);
        }
    }

    String getValidAccessTokenByUserId(UUID userId) {
        GoogleToken token = googleTokenRepository.findByUserId(userId)
                .orElseThrow(() -> new ServiceException(
                        "Google Calendar is not connected. Please connect your calendar first.",
                        HttpStatus.PRECONDITION_REQUIRED));

        if (token.getExpiresAt().isBefore(java.time.Instant.now().plusSeconds(60))) {
            GoogleTokenResponse refreshed = googleAuthService.refreshAccessToken(token.getRefreshToken());
            token.setAccessToken(refreshed.accessToken());
            token.setExpiresAt(java.time.Instant.now().plusSeconds(refreshed.expiresIn() - 30));
            googleTokenRepository.save(token);
        }

        return token.getAccessToken();
    }

    // ─── Google event response records (for per-user priority view) ──────────

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    record GoogleEventItem(
            String id,
            String summary,
            @com.fasterxml.jackson.annotation.JsonProperty("htmlLink") String htmlLink,
            GoogleDateTime start,
            GoogleDateTime end,
            @com.fasterxml.jackson.annotation.JsonProperty("conferenceData") GoogleConferenceData conferenceData
    ) {}

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    record GoogleConferenceData(
            @com.fasterxml.jackson.annotation.JsonProperty("entryPoints") List<GoogleEntryPoint> entryPoints
    ) {}

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    record GoogleEntryPoint(
            @com.fasterxml.jackson.annotation.JsonProperty("entryPointType") String entryPointType,
            @com.fasterxml.jackson.annotation.JsonProperty("uri") String uri
    ) {}

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    record GoogleDateTime(
            @com.fasterxml.jackson.annotation.JsonProperty("dateTime") String dateTime,
            @com.fasterxml.jackson.annotation.JsonProperty("date") String date
    ) {}
}
