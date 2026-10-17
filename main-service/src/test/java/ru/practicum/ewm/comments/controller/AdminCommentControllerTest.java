package ru.practicum.ewm.comments.controller;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;
import ru.practicum.ewm.comments.dto.CommentDto;
import ru.practicum.ewm.comments.service.AdminCommentService;
import ru.practicum.ewm.exception.NotFoundException;
import ru.practicum.ewm.exception.ValidationException;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AdminCommentController.class)
class AdminCommentControllerTest {
    private static final LocalDateTime CREATED = LocalDateTime.of(2026, 10, 14, 12, 0, 0);

    @Autowired
    private MockMvc mvc;
    @MockBean
    private AdminCommentService service;

    @Test
    void findCommentsParsesAllFilters() throws Exception {
        LocalDateTime start = LocalDateTime.of(2026, 10, 1, 0, 0, 0);
        LocalDateTime end = LocalDateTime.of(2026, 10, 31, 23, 59, 59);
        when(service.findComments(List.of(1L, 2L), List.of(10L), start, end, 5, 20)).thenReturn(List.of(dto(7L)));

        mvc.perform(get("/admin/comments")
                        .param("users", "1", "2")
                        .param("events", "10")
                        .param("rangeStart", "2026-10-01 00:00:00")
                        .param("rangeEnd", "2026-10-31 23:59:59")
                        .param("from", "5")
                        .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(7))
                .andExpect(jsonPath("$[0].text").value("Text"))
                .andExpect(jsonPath("$[0].authorName").value("Author"))
                .andExpect(jsonPath("$[0].eventId").value(10))
                .andExpect(jsonPath("$[0].created").value("2026-10-14 12:00:00"));
    }

    @Test
    void findCommentsUsesDefaultPagination() throws Exception {
        when(service.findComments(null, null, null, null, 0, 10)).thenReturn(List.of());

        mvc.perform(get("/admin/comments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
        verify(service).findComments(null, null, null, null, 0, 10);
    }

    @ParameterizedTest
    @CsvSource({
            "from,-1",
            "size,0",
            "size,-5",
            "rangeStart,01.10.2026 00:00",
            "rangeEnd,not-a-date",
            "users,abc"
    })
    void invalidParametersReturn400(String name, String value) throws Exception {
        mvc.perform(get("/admin/comments").param(name, value))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("BAD_REQUEST"));
        verifyNoInteractions(service);
    }

    @Test
    void rangeStartAfterRangeEndReturns400() throws Exception {
        when(service.findComments(any(), any(), any(), any(), anyInt(), anyInt()))
                .thenThrow(new ValidationException("Начало диапазона не может быть позже его окончания"));

        mvc.perform(get("/admin/comments")
                        .param("rangeStart", "2026-10-31 00:00:00")
                        .param("rangeEnd", "2026-10-01 00:00:00"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("BAD_REQUEST"));
    }

    @Test
    void getCommentReturns200Or404() throws Exception {
        when(service.getComment(7L)).thenReturn(dto(7L));
        when(service.getComment(8L)).thenThrow(new NotFoundException("Комментарий с id=8 не найден"));

        mvc.perform(get("/admin/comments/7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(7));
        mvc.perform(get("/admin/comments/8"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value("NOT_FOUND"));
    }

    @Test
    void deleteCommentReturns204() throws Exception {
        mvc.perform(delete("/admin/comments/7"))
                .andExpect(status().isNoContent());
        verify(service).deleteComment(7L);
    }

    @Test
    void deleteMissingCommentReturns404() throws Exception {
        doThrow(new NotFoundException("Комментарий с id=8 не найден")).when(service).deleteComment(8L);

        mvc.perform(delete("/admin/comments/8"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value("NOT_FOUND"));
    }

    @Test
    void nonPositiveCommentIdReturns400() throws Exception {
        mvc.perform(get("/admin/comments/0"))
                .andExpect(status().isBadRequest());
        mvc.perform(delete("/admin/comments/-1"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    private CommentDto dto(Long id) {
        return CommentDto.builder()
                .id(id)
                .text("Text")
                .authorName("Author")
                .eventId(10L)
                .created(CREATED)
                .build();
    }
}
