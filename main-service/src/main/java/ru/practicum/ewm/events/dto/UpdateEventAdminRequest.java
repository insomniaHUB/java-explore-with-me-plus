package ru.practicum.ewm.events.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class UpdateEventAdminRequest extends EventUpdateRequest {
    private AdminStateAction stateAction;
}
