package de.zalando.aruha.nakadi.repository;

import javax.annotation.Nullable;

import de.zalando.aruha.nakadi.NakadiException;
import de.zalando.aruha.nakadi.domain.EventType;

public interface EventTypeRepository {

    void saveEventType(EventType eventType) throws NakadiException;

    @Nullable
    EventType findByName(final String eventTypeName) throws NoSuchEventTypeException;

}
