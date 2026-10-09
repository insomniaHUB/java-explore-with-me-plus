package ru.practicum.ewm.compilations.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class CompilationRequestTest {
    private static ValidatorFactory validatorFactory;
    private static Validator validator;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeAll
    static void setUpValidator() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        validatorFactory.close();
    }

    @Test
    void createsEmptyUnpinnedCompilationWhenOptionalFieldsAreOmitted() throws Exception {
        NewCompilationDto dto = objectMapper.readValue("{\"title\":\"Концерты\"}", NewCompilationDto.class);

        assertThat(dto.getPinned()).isFalse();
        assertThat(dto.getEvents()).isEmpty();
        assertThat(validator.validate(dto)).isEmpty();
    }

    @Test
    void keepsCreationDefaultsForExplicitNulls() throws Exception {
        NewCompilationDto dto = objectMapper.readValue(
                "{\"title\":\"Концерты\",\"pinned\":null,\"events\":null}", NewCompilationDto.class);

        assertThat(dto.getPinned()).isFalse();
        assertThat(dto.getEvents()).isEmpty();
    }

    @Test
    void removesDuplicateEventIds() throws Exception {
        NewCompilationDto dto = objectMapper.readValue(
                "{\"title\":\"Концерты\",\"pinned\":true,\"events\":[1,1,2]}", NewCompilationDto.class);

        assertThat(dto.getPinned()).isTrue();
        assertThat(dto.getEvents()).containsExactlyInAnyOrder(1L, 2L);
        assertThat(validator.validate(dto)).isEmpty();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t"})
    void rejectsMissingOrBlankTitleOnCreation(String title) {
        NewCompilationDto dto = NewCompilationDto.builder().title(title).build();

        assertThat(validator.validate(dto))
                .extracting(violation -> violation.getPropertyPath().toString())
                .contains("title");
    }

    @Test
    void checksTitleLengthOnCreationAndUpdate() {
        NewCompilationDto created = NewCompilationDto.builder().title("x".repeat(50)).build();
        UpdateCompilationRequest updated = UpdateCompilationRequest.builder().title("x".repeat(50)).build();
        assertThat(validator.validate(created)).isEmpty();
        assertThat(validator.validate(updated)).isEmpty();

        created.setTitle("x".repeat(51));
        updated.setTitle("x".repeat(51));
        assertThat(validator.validate(created)).hasSize(1);
        assertThat(validator.validate(updated)).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"title\":null,\"pinned\":null,\"events\":null}"})
    void missingOrNullUpdateFieldsLeaveExistingValuesUnchanged(String json) throws Exception {
        UpdateCompilationRequest dto = objectMapper.readValue(json, UpdateCompilationRequest.class);

        assertThat(dto.getTitle()).isNull();
        assertThat(dto.getPinned()).isNull();
        assertThat(dto.getEvents()).isNull();
        assertThat(validator.validate(dto)).isEmpty();
    }

    @Test
    void preservesExplicitFalseAndEmptyEventsOnUpdate() throws Exception {
        UpdateCompilationRequest dto = objectMapper.readValue(
                "{\"pinned\":false,\"events\":[]}", UpdateCompilationRequest.class);

        assertThat(dto.getPinned()).isFalse();
        assertThat(dto.getEvents()).isEmpty();
        assertThat(dto.getTitle()).isNull();
        assertThat(validator.validate(dto)).isEmpty();
    }

    @Test
    void rejectsEmptyTitleOnUpdate() {
        UpdateCompilationRequest dto = UpdateCompilationRequest.builder().title("").build();

        assertThat(validator.validate(dto))
                .extracting(violation -> violation.getPropertyPath().toString())
                .containsExactly("title");
    }

    @Test
    void rejectsNullEventIds() throws Exception {
        NewCompilationDto created = objectMapper.readValue(
                "{\"title\":\"Концерты\",\"events\":[null]}", NewCompilationDto.class);
        UpdateCompilationRequest updated = objectMapper.readValue("{\"events\":[null]}", UpdateCompilationRequest.class);

        assertThat(validator.validate(created)).hasSize(1);
        assertThat(validator.validate(updated)).hasSize(1);
    }
}
