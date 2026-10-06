package ru.practicum.ewm.events.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import ru.practicum.ewm.events.dto.EventFullDto;
import ru.practicum.ewm.events.dto.NewEventDto;
import ru.practicum.ewm.events.dto.UpdateEventUserRequest;
import ru.practicum.ewm.events.model.EventState;
import ru.practicum.ewm.events.service.EventService;
import ru.practicum.ewm.exception.ConflictException;
import ru.practicum.ewm.exception.NotFoundException;
import ru.practicum.ewm.exception.ServiceUnavailableException;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest({PrivateEventController.class, AdminEventController.class})
class EventControllerTest {
    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper objectMapper;
    @MockBean
    private EventService service;

    @Test
    void createReturns201AndUsesApiDateFormat() throws Exception {
        when(service.create(eq(1L), any())).thenReturn(EventFullDto.builder()
                .id(5L).state(EventState.PENDING).eventDate(LocalDateTime.of(2026, 10, 7, 12, 0)).build());
        mvc.perform(post("/users/1/events").contentType(MediaType.APPLICATION_JSON).content(validBody().toString()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(5))
                .andExpect(jsonPath("$.state").value("PENDING"))
                .andExpect(jsonPath("$.eventDate").value("2026-10-07 12:00:00"));
        ArgumentCaptor<NewEventDto> dto = ArgumentCaptor.forClass(NewEventDto.class);
        verify(service).create(eq(1L), dto.capture());
        assertThat(dto.getValue().getEventDate()).isEqualTo(LocalDateTime.of(2026, 10, 7, 12, 0));
    }

    @ParameterizedTest
    @ValueSource(strings = {"annotation", "description", "title", "category", "eventDate", "location"})
    void requiredCreationFieldsReturn400(String field) throws Exception {
        ObjectNode body = validBody();
        body.remove(field);
        mvc.perform(post("/users/1/events").contentType(MediaType.APPLICATION_JSON).content(body.toString()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.status").value("BAD_REQUEST"));
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @CsvSource({"title,2", "title,121", "annotation,19", "annotation,2001", "description,19", "description,7001"})
    void validatesCreationAndUserPatchLengths(String field, int length) throws Exception {
        ObjectNode body = validBody().put(field, "a".repeat(length));
        mvc.perform(post("/users/1/events").contentType(MediaType.APPLICATION_JSON).content(body.toString()))
                .andExpect(status().isBadRequest());
        mvc.perform(patch("/users/1/events/5").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.createObjectNode().put(field, "a".repeat(length)).toString()))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026-02-30 12:00:00", "2026-10-07T12:00:00", "bad"})
    void rejectsMalformedDateWithApiError(String date) throws Exception {
        mvc.perform(post("/users/1/events").contentType(MediaType.APPLICATION_JSON)
                        .content(validBody().put("eventDate", date).toString()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.status").value("BAD_REQUEST"));
        verifyNoInteractions(service);
    }

    @Test
    void rejectsNegativeLimitAndIncompleteLocation() throws Exception {
        mvc.perform(post("/users/1/events").contentType(MediaType.APPLICATION_JSON)
                        .content(validBody().put("participantLimit", -1).toString()))
                .andExpect(status().isBadRequest());
        ObjectNode body = validBody();
        ((ObjectNode) body.get("location")).remove("lat");
        mvc.perform(post("/users/1/events").contentType(MediaType.APPLICATION_JSON).content(body.toString()))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void patchPreservesNullFalseAndZero() throws Exception {
        mvc.perform(patch("/users/1/events/5").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":null,\"paid\":false,\"participantLimit\":0,\"requestModeration\":false}"))
                .andExpect(status().isOk());
        ArgumentCaptor<UpdateEventUserRequest> dto = ArgumentCaptor.forClass(UpdateEventUserRequest.class);
        verify(service).updateOwn(eq(1L), eq(5L), dto.capture());
        assertThat(dto.getValue().getTitle()).isNull();
        assertThat(dto.getValue().getPaid()).isFalse();
        assertThat(dto.getValue().getParticipantLimit()).isZero();
        assertThat(dto.getValue().getRequestModeration()).isFalse();
    }

    @Test
    void adminPatchDoesNotApplyUserTextValidation() throws Exception {
        mvc.perform(patch("/admin/events/5").contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"ab\"}"))
                .andExpect(status().isOk());
        verify(service).updateAdmin(eq(5L), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/users/1/events", "/admin/events"})
    void rejectsZeroSizeAndNegativeOffset(String path) throws Exception {
        mvc.perform(get(path).param("size", "0")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("BAD_REQUEST"));
        mvc.perform(get(path).param("from", "-1")).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void bindsAdminFiltersWithoutRoundingOffset() throws Exception {
        mvc.perform(get("/admin/events").param("users", "1", "2").param("states", "PENDING")
                        .param("categories", "3").param("rangeStart", "2026-10-06 12:00:00")
                        .param("from", "1").param("size", "2"))
                .andExpect(status().isOk());
        verify(service).findAdmin(List.of(1L, 2L), List.of(EventState.PENDING), List.of(3L),
                LocalDateTime.of(2026, 10, 6, 12, 0), null, 1, 2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{broken", "", "null", "{\"stateAction\":\"PUBLISH_EVENT\"}"})
    void rejectsMalformedBodyAndAdminActionOnPrivateEndpoint(String body) throws Exception {
        mvc.perform(patch("/users/1/events/5").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void returns404409And503AsApiError() throws Exception {
        when(service.getOwn(1L, 5L)).thenThrow(new NotFoundException("missing"));
        mvc.perform(get("/users/1/events/5")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value("NOT_FOUND"));
        when(service.updateAdmin(eq(5L), any())).thenThrow(new ConflictException("already published"));
        mvc.perform(patch("/admin/events/5").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.status").value("CONFLICT"));
        doThrow(new ServiceUnavailableException("offline")).when(service).getOwn(1L, 5L);
        mvc.perform(get("/users/1/events/5")).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("SERVICE_UNAVAILABLE"));
    }

    private ObjectNode validBody() {
        ObjectNode body = objectMapper.createObjectNode()
                .put("annotation", "An annotation long enough for validation")
                .put("description", "A description long enough for validation")
                .put("title", "Event title").put("category", 2)
                .put("eventDate", "2026-10-07 12:00:00");
        body.putObject("location").put("lat", 55.7).put("lon", 37.6);
        return body;
    }
}
