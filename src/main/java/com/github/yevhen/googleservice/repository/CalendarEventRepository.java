package com.github.yevhen.googleservice.repository;

import com.github.yevhen.googleservice.model.CalendarEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public interface CalendarEventRepository extends JpaRepository<CalendarEvent, UUID> {
    List<CalendarEvent> findByIdInAndDeletedFalseAndStartDateTimeBetween(
            List<UUID> ids,
            OffsetDateTime start,
            OffsetDateTime end
    );
}
