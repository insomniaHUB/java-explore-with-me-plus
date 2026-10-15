package ru.practicum.ewm.comments.controller;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;
import ru.practicum.ewm.comments.dto.CommentDto;
import ru.practicum.ewm.comments.service.PublicCommentService;
import ru.practicum.ewm.exception.NotFoundException;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PublicCommentController.class)
class PublicCommentControllerTest {
    @Autowired
    private MockMvc mvc;
    @MockBean
    private PublicCommentService service;

    @Test
    void returnsCommentFieldsAndUsesDefaultPagination() throws Exception {
        CommentDto comment = CommentDto.builder().id(7L).text("A comment").authorName("Author").eventId(10L)
                .created(LocalDateTime.of(2026, 10, 14, 12, 0)).build();
        when(service.findForEvent(10L, 0, 10)).thenReturn(List.of(comment));

        mvc.perform(get("/events/10/comments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(7))
                .andExpect(jsonPath("$[0].text").value("A comment"))
                .andExpect(jsonPath("$[0].authorName").value("Author"))
                .andExpect(jsonPath("$[0].eventId").value(10))
                .andExpect(jsonPath("$[0].created").value("2026-10-14 12:00:00"));
        verify(service).findForEvent(10L, 0, 10);
    }

    @Test
    void passesExactOffsetAndSizeToService() throws Exception {
        mvc.perform(get("/events/10/comments").param("from", "1").param("size", "2"))
                .andExpect(status().isOk());
        verify(service).findForEvent(10L, 1, 2);
    }

    @ParameterizedTest
    @CsvSource({"0,0,10", "-1,0,10", "abc,0,10", "1,-1,10", "1,abc,10", "1,0,0", "1,0,-1",
            "1,0,abc", "1,0,2147483648"})
    void invalidParametersReturn400WithoutCallingService(String eventId, String from, String size) throws Exception {
        mvc.perform(get("/events/{eventId}/comments", eventId).param("from", from).param("size", size))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("BAD_REQUEST"));
        verifyNoInteractions(service);
    }

    @Test
    void eventWithoutCommentsReturnsEmptyArray() throws Exception {
        when(service.findForEvent(10L, 0, 10)).thenReturn(List.of());

        mvc.perform(get("/events/10/comments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void unavailableEventReturns404() throws Exception {
        when(service.findForEvent(10L, 0, 10)).thenThrow(new NotFoundException("Опубликованное событие не найдено"));

        mvc.perform(get("/events/10/comments"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value("NOT_FOUND"));
    }
}
