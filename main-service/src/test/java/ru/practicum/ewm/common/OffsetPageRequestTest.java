package ru.practicum.ewm.common;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OffsetPageRequestTest {

    @ParameterizedTest
    @CsvSource({"0,10", "1,2", "3,2", "25,10", "10,10"})
    void preservesExactOffset(int from, int size) {
        Pageable pageable = new OffsetPageRequest(from, size);

        assertThat(pageable.getOffset()).isEqualTo(from);
        assertThat(pageable.getPageSize()).isEqualTo(size);
    }

    @Test
    void navigationPreservesOffsetAndSorting() {
        Sort sort = Sort.by("id");
        Pageable pageable = new OffsetPageRequest(3, 2, sort);

        assertThat(pageable.next().getOffset()).isEqualTo(5);
        assertThat(pageable.next().previousOrFirst()).isEqualTo(pageable);
        assertThat(pageable.previousOrFirst().getOffset()).isEqualTo(1);
        assertThat(pageable.previousOrFirst().previousOrFirst().getOffset()).isZero();
        assertThat(pageable.first().hasPrevious()).isFalse();
        assertThat(pageable.hasPrevious()).isTrue();
        assertThat(pageable.withPage(4).getOffset()).isEqualTo(8);
        assertThat(pageable.next().getSort()).isEqualTo(sort);
    }

    @ParameterizedTest
    @CsvSource({"-1,10", "0,0", "0,-1"})
    void rejectsInvalidPagination(int from, int size) {
        assertThatThrownBy(() -> new OffsetPageRequest(from, size))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNegativePageNumber() {
        Pageable pageable = new OffsetPageRequest(0, 10);

        assertThatThrownBy(() -> pageable.withPage(-1)).isInstanceOf(IllegalArgumentException.class);
    }
}
