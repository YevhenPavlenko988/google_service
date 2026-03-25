package com.github.yevhen.googleservice.event;

import com.github.yevhen.googleservice.service.CalendarSyncService;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Triggers Google Calendar sync only after the DB transaction commits,
 * so the async sync thread can see all newly saved data.
 */
@Component
public class CalendarSyncListener {

    private final CalendarSyncService calendarSyncService;

    public CalendarSyncListener(CalendarSyncService calendarSyncService) {
        this.calendarSyncService = calendarSyncService;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSyncCalendarEvent(SyncCalendarEvent event) {
        calendarSyncService.syncEvent(event.eventId());
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDeleteCalendarEvent(DeleteCalendarEvent event) {
        calendarSyncService.deleteEventFromGoogle(event.eventId());
    }
}
