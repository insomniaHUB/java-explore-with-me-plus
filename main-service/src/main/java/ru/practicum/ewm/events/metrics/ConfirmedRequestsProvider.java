package ru.practicum.ewm.events.metrics;

import java.util.Map;
import java.util.Set;

/** Контракт для части заявок: один пакетный подсчёт только CONFIRMED, без хранимого счётчика. */
public interface ConfirmedRequestsProvider {
    Map<Long, Long> countConfirmedRequests(Set<Long> eventIds);
}
