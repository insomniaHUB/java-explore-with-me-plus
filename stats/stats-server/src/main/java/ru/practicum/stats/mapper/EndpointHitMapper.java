package ru.practicum.stats.mapper;

import ru.practicum.dto.EndpointHitDto;
import ru.practicum.stats.model.EndpointHit;

public final class EndpointHitMapper {

    private EndpointHitMapper() {
    }

    public static EndpointHit toEntity(EndpointHitDto dto) {
        return new EndpointHit(dto.app(), dto.uri(), dto.ip(), dto.timestamp());
    }
}
