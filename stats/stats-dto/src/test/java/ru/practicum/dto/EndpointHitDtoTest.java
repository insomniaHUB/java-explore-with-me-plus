package ru.practicum.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class EndpointHitDtoTest {

    private static final LocalDateTime TIMESTAMP = LocalDateTime.of(2022, 9, 6, 11, 0, 23);

    private static ValidatorFactory validatorFactory;
    private static Validator validator;

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @BeforeAll
    static void setUp() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        validatorFactory.close();
    }

    @Test
    void serializesTimestampInSpecFormat() throws Exception {
        EndpointHitDto dto = new EndpointHitDto("ewm-main-service", "/events/1", "192.163.0.1", TIMESTAMP);

        JsonNode json = mapper.readTree(mapper.writeValueAsString(dto));

        assertThat(json.get("app").asText()).isEqualTo("ewm-main-service");
        assertThat(json.get("uri").asText()).isEqualTo("/events/1");
        assertThat(json.get("ip").asText()).isEqualTo("192.163.0.1");
        assertThat(json.get("timestamp").asText()).isEqualTo("2022-09-06 11:00:23");
    }

    @Test
    void deserializesFromSpecExample() throws Exception {
        String json = """
                {
                  "app": "ewm-main-service",
                  "uri": "/events/1",
                  "ip": "192.163.0.1",
                  "timestamp": "2022-09-06 11:00:23"
                }
                """;

        EndpointHitDto dto = mapper.readValue(json, EndpointHitDto.class);

        assertThat(dto).isEqualTo(new EndpointHitDto("ewm-main-service", "/events/1", "192.163.0.1", TIMESTAMP));
    }

    @Test
    void validDtoHasNoViolations() {
        EndpointHitDto dto = new EndpointHitDto("ewm-main-service", "/events/1", "192.163.0.1", TIMESTAMP);

        assertThat(validator.validate(dto)).isEmpty();
    }

    @Test
    void blankAndMissingFieldsAreRejected() {
        EndpointHitDto dto = new EndpointHitDto(" ", "", null, null);

        Set<ConstraintViolation<EndpointHitDto>> violations = validator.validate(dto);

        assertThat(violations)
                .extracting(violation -> violation.getPropertyPath().toString())
                .containsExactlyInAnyOrder("app", "uri", "ip", "timestamp");
    }

    @Test
    void tooLongIpIsRejected() {
        EndpointHitDto dto = new EndpointHitDto("ewm-main-service", "/events/1", "1".repeat(46), TIMESTAMP);

        assertThat(validator.validate(dto))
                .extracting(violation -> violation.getPropertyPath().toString())
                .containsExactly("ip");
    }
}
