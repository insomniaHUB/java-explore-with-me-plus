package ru.practicum.ewm.requests.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import ru.practicum.ewm.exception.ConflictException;
import ru.practicum.ewm.exception.NotFoundException;
import ru.practicum.ewm.requests.dto.EventRequestStatusUpdateRequest;
import ru.practicum.ewm.requests.dto.EventRequestStatusUpdateResult;
import ru.practicum.ewm.requests.dto.ParticipationRequestDto;
import ru.practicum.ewm.requests.dto.RequestUpdateStatus;
import ru.practicum.ewm.requests.model.RequestStatus;
import ru.practicum.ewm.requests.service.RequestService;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest({PrivateRequestController.class, PrivateEventRequestController.class})
class RequestControllerTest {
    private static final LocalDateTime CREATED = LocalDateTime.of(2026, 10, 6, 12, 0, 0, 123_000_000);

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper objectMapper;
    @MockBean
    private RequestService service;

    @Test
    void createReturns201WithAllFields() throws Exception {
        when(service.create(2L, 10L)).thenReturn(dto(7L, RequestStatus.PENDING));

        mvc.perform(post("/users/2/requests").param("eventId", "10"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(7))
                .andExpect(jsonPath("$.event").value(10))
                .andExpect(jsonPath("$.requester").value(2))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.created").value("2026-10-06T12:00:00.123"));
    }

    @Test
    void createWithoutEventIdReturns400() throws Exception {
        mvc.perform(post("/users/2/requests"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("BAD_REQUEST"));
        verifyNoInteractions(service);
    }

    @Test
    void conflictAndNotFoundAreMappedToApiError() throws Exception {
        when(service.create(2L, 10L)).thenThrow(new ConflictException("Заявка уже подана"));
        when(service.cancel(2L, 7L)).thenThrow(new NotFoundException("Заявка не найдена"));

        mvc.perform(post("/users/2/requests").param("eventId", "10"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value("CONFLICT"));
        mvc.perform(patch("/users/2/requests/7/cancel"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value("NOT_FOUND"));
    }

    @Test
    void ownRequestsAndCancelReturn200() throws Exception {
        when(service.findOwn(2L)).thenReturn(List.of(dto(7L, RequestStatus.PENDING)));
        when(service.cancel(2L, 7L)).thenReturn(dto(7L, RequestStatus.CANCELED));

        mvc.perform(get("/users/2/requests"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(7));
        mvc.perform(patch("/users/2/requests/7/cancel"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELED"));
    }

    @Test
    void eventRequestsAreReturnedToInitiator() throws Exception {
        when(service.findForEvent(1L, 10L)).thenReturn(List.of(dto(7L, RequestStatus.PENDING)));

        mvc.perform(get("/users/1/events/10/requests"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].event").value(10));
    }

    @Test
    void updateReturnsConfirmedAndRejectedLists() throws Exception {
        EventRequestStatusUpdateRequest request = EventRequestStatusUpdateRequest.builder()
                .requestIds(List.of(7L, 8L)).status(RequestUpdateStatus.CONFIRMED).build();
        when(service.updateStatuses(eq(1L), eq(10L), any())).thenReturn(EventRequestStatusUpdateResult.builder()
                .confirmedRequests(List.of(dto(7L, RequestStatus.CONFIRMED)))
                .rejectedRequests(List.of(dto(8L, RequestStatus.REJECTED)))
                .build());

        mvc.perform(patch("/users/1/events/10/requests").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.confirmedRequests[0].status").value("CONFIRMED"))
                .andExpect(jsonPath("$.rejectedRequests[0].status").value("REJECTED"));
        verify(service).updateStatuses(1L, 10L, request);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"status\":\"CONFIRMED\"}",
            "{\"requestIds\":[],\"status\":\"CONFIRMED\"}",
            "{\"requestIds\":[7]}",
            "{\"requestIds\":[7],\"status\":\"CANCELED\"}",
            "{\"requestIds\":[-1],\"status\":\"REJECTED\"}"
    })
    void invalidUpdateBodyReturns400(String body) throws Exception {
        mvc.perform(patch("/users/1/events/10/requests").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("BAD_REQUEST"));
        verifyNoInteractions(service);
    }

    private ParticipationRequestDto dto(Long id, RequestStatus status) {
        return ParticipationRequestDto.builder()
                .id(id)
                .event(10L)
                .requester(2L)
                .status(status)
                .created(CREATED)
                .build();
    }
}
