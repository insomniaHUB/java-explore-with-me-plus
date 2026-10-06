package ru.practicum.ewm.events.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class UpdateEventUserRequest extends EventUpdateRequest {
    private UserStateAction stateAction;
}
