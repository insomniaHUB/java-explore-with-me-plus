package ru.practicum.ewm.comments.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import ru.practicum.ewm.comments.dto.CommentDto;
import ru.practicum.ewm.comments.dto.UpdateCommentDto;
import ru.practicum.ewm.comments.service.CommentService;
import ru.practicum.ewm.exception.NotFoundException;

import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PrivateCommentController.class)
class CommentControllerTest {
    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper objectMapper;
    @MockBean
    private CommentService commentService;

    @Test
    void updateReturnsUpdatedComment() throws Exception {
        UpdateCommentDto request = UpdateCommentDto.builder().text("New text").build();
        when(commentService.updateComment(any(), eq(1L), eq(5L))).thenReturn(CommentDto.builder()
                .id(5L)
                .text("New text")
                .authorName("Author")
                .eventId(10L)
                .created(LocalDateTime.of(2026, 10, 14, 12, 0))
                .build());

        mvc.perform(patch("/users/1/comments/5")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(5))
                .andExpect(jsonPath("$.text").value("New text"))
                .andExpect(jsonPath("$.authorName").value("Author"))
                .andExpect(jsonPath("$.eventId").value(10))
                .andExpect(jsonPath("$.created").value("2026-10-14 12:00:00"));
        verify(commentService).updateComment(request, 1L, 5L);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"text\":null}", "{\"text\":\"\"}", "{\"text\":\"   \"}"})
    void invalidUpdateBodyReturns400(String body) throws Exception {
        mvc.perform(patch("/users/1/comments/5")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("BAD_REQUEST"));
        verifyNoInteractions(commentService);
    }

    @Test
    void tooLongUpdateTextReturns400() throws Exception {
        mvc.perform(patch("/users/1/comments/5")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpdateCommentDto("a".repeat(2001)))))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(commentService);
    }

    @Test
    void deleteReturns204() throws Exception {
        mvc.perform(delete("/users/1/comments/5"))
                .andExpect(status().isNoContent());
        verify(commentService).deleteComment(1L, 5L);
    }

    @Test
    void nonPositivePathIdsReturn400() throws Exception {
        mvc.perform(delete("/users/0/comments/5"))
                .andExpect(status().isBadRequest());
        mvc.perform(delete("/users/1/comments/0"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(commentService);
    }

    @Test
    void inaccessibleCommentReturns404() throws Exception {
        when(commentService.updateComment(any(), eq(2L), eq(5L)))
                .thenThrow(new NotFoundException("Комментарий не найден"));

        mvc.perform(patch("/users/2/comments/5")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"New text\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value("NOT_FOUND"));
    }
}
