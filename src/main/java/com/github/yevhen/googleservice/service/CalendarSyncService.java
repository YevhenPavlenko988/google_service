package com.github.yevhen.googleservice.service;

import com.github.yevhen.googleservice.config.GoogleProperties;
import com.github.yevhen.googleservice.dto.GoogleTokenResponse;
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
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class CalendarSyncService {

    private static final Logger log = LoggerFactory.getLogger(CalendarSyncService.class);

    private final CalendarEventRepository calendarEventRepository;
    private final EventParticipantRepository participantRepository;
    private final EventSyncStatusRepository syncStatusRepository;
    private final GoogleTokenRepository googleTokenRepository;
    private final GoogleAuthService googleAuthService;
    private final GoogleProperties googleProps;
    private final RestClient restClient;

    public CalendarSyncService(
            CalendarEventRepository calendarEventRepository,
            EventParticipantRepository participantRepository,
            EventSyncStatusRepository syncStatusRepository,
            GoogleTokenRepository googleTokenRepository,
            GoogleAuthService googleAuthService,
            GoogleProperties googleProps,
            RestClient restClient
    ) {
        this.calendarEventRepository = calendarEventRepository;
        this.participantRepository = participantRepository;
        this.syncStatusRepository = syncStatusRepository;
        this.googleTokenRepository = googleTokenRepository;
        this.googleAuthService = googleAuthService;
        this.googleProps = googleProps;
        this.restClient = restClient;
    }

    @Async
    public void syncEvent(UUID eventId) {
        CalendarEvent event = calendarEventRepository.findById(eventId).orElse(null);
        if (event == null || event.isDeleted()) {
            return;
        }

        List<EventParticipant> participants = participantRepository.findByEventId(eventId);
        List<String> attendeeEmails = participants.stream()
                .map(EventParticipant::getParticipantEmail)
                .distinct()
                .toList();

        Set<UUID> activeUserIds = participants.stream()
                .map(EventParticipant::getParticipantUserId)
                .filter(id -> id != null)
                .collect(Collectors.toSet());

        List<EventSyncStatus> statuses = syncStatusRepository.findByEventId(eventId);
        for (EventSyncStatus status : statuses) {
            if (!activeUserIds.contains(status.getParticipantUserId())) {
                deleteFromGoogleIfExists(status);
                syncStatusRepository.delete(status);
            }
        }

        for (EventParticipant participant : participants) {
            UUID participantUserId = participant.getParticipantUserId();
            if (participantUserId == null) {
                continue;
            }

            EventSyncStatus status = syncStatusRepository.findByEventIdAndParticipantUserId(eventId, participantUserId)
                    .orElseGet(() -> new EventSyncStatus(UUID.randomUUID(), eventId, participantUserId, EventSyncStatus.SyncStatus.PENDING));

            try {
                GoogleToken token = googleTokenRepository.findByUserId(participantUserId).orElse(null);
                if (token == null) {
                    status.setStatus(EventSyncStatus.SyncStatus.NOT_CONNECTED);
                    status.setLastError("Google Calendar is not connected");
                    syncStatusRepository.save(status);
                    continue;
                }

                String accessToken = ensureValidAccessToken(token);
                Map<String, Object> body = buildEventBody(event, attendeeEmails);
                if (status.getGoogleEventId() == null || status.getGoogleEventId().isBlank()) {
                    Map<?, ?> created = restClient.post()
                            .uri(googleProps.getCalendarBaseUrl() + "/calendars/primary/events?sendUpdates=all&conferenceDataVersion=1")
                            .header("Authorization", "Bearer " + accessToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .body(body)
                            .retrieve()
                            .body(Map.class);
                    status.setGoogleEventId(created != null ? (String) created.get("id") : null);
                } else {
                    restClient.put()
                            .uri(googleProps.getCalendarBaseUrl() + "/calendars/primary/events/" + status.getGoogleEventId()
                                    + "?sendUpdates=all&conferenceDataVersion=1")
                            .header("Authorization", "Bearer " + accessToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .body(body)
                            .retrieve()
                            .toBodilessEntity();
                }
                status.setStatus(EventSyncStatus.SyncStatus.SYNCED);
                status.setLastError(null);
                syncStatusRepository.save(status);
            } catch (Exception e) {
                status.setStatus(EventSyncStatus.SyncStatus.FAILED);
                status.setLastError(e.getMessage());
                syncStatusRepository.save(status);
                log.warn("Event sync failed for eventId={} userId={}: {}", eventId, participantUserId, e.getMessage());
            }
        }
    }

    @Async
    public void deleteEventFromGoogle(UUID eventId) {
        List<EventSyncStatus> statuses = syncStatusRepository.findByEventId(eventId);
        for (EventSyncStatus status : statuses) {
            try {
                deleteFromGoogleIfExists(status);
            } catch (Exception e) {
                status.setStatus(EventSyncStatus.SyncStatus.FAILED);
                status.setLastError(e.getMessage());
                syncStatusRepository.save(status);
                log.warn("Event delete sync failed for eventId={} userId={}: {}", eventId, status.getParticipantUserId(), e.getMessage());
            }
        }
    }

    private void deleteFromGoogleIfExists(EventSyncStatus status) {
        if (status.getGoogleEventId() == null || status.getGoogleEventId().isBlank()) {
            return;
        }
        GoogleToken token = googleTokenRepository.findByUserId(status.getParticipantUserId()).orElse(null);
        if (token == null) {
            return;
        }
        String accessToken = ensureValidAccessToken(token);
        restClient.delete()
                .uri(googleProps.getCalendarBaseUrl() + "/calendars/primary/events/" + status.getGoogleEventId())
                .header("Authorization", "Bearer " + accessToken)
                .retrieve()
                .toBodilessEntity();
    }

    private String ensureValidAccessToken(GoogleToken token) {
        if (token.getExpiresAt().isBefore(Instant.now().plusSeconds(60))) {
            GoogleTokenResponse refreshed = googleAuthService.refreshAccessToken(token.getRefreshToken());
            token.setAccessToken(refreshed.accessToken());
            token.setExpiresAt(Instant.now().plusSeconds(refreshed.expiresIn() - 30));
            googleTokenRepository.save(token);
        }
        return token.getAccessToken();
    }

    private Map<String, Object> buildEventBody(CalendarEvent event, List<String> attendeeEmails) {
        Map<String, Object> body = new HashMap<>();
        body.put("summary", event.getTitle());
        body.put("start", Map.of("dateTime", event.getStartDateTime().toString()));
        body.put("end", Map.of("dateTime", event.getEndDateTime().toString()));
        body.put("attendees", attendeeEmails.stream().map(email -> Map.of("email", email)).toList());
        body.put("conferenceData", Map.of(
                "createRequest", Map.of(
                        "requestId", UUID.randomUUID().toString(),
                        "conferenceSolutionKey", Map.of("type", "hangoutsMeet")
                )
        ));
        return body;
    }
}
