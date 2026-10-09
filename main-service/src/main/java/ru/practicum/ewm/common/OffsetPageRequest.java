package ru.practicum.ewm.common;

import lombok.EqualsAndHashCode;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.util.Assert;

@EqualsAndHashCode
public final class OffsetPageRequest implements Pageable {
    private final int from;
    private final int size;
    private final Sort sort;

    public OffsetPageRequest(int from, int size) {
        this(from, size, Sort.unsorted());
    }

    public OffsetPageRequest(int from, int size, Sort sort) {
        Assert.isTrue(from >= 0, "Смещение не должно быть отрицательным");
        Assert.isTrue(size > 0, "Размер страницы должен быть положительным");
        Assert.notNull(sort, "Сортировка не должна быть null");
        this.from = from;
        this.size = size;
        this.sort = sort;
    }

    @Override
    public int getPageNumber() {
        return from / size;
    }

    @Override
    public int getPageSize() {
        return size;
    }

    @Override
    public long getOffset() {
        return from;
    }

    @Override
    public Sort getSort() {
        return sort;
    }

    @Override
    public Pageable next() {
        return new OffsetPageRequest(Math.addExact(from, size), size, sort);
    }

    @Override
    public Pageable previousOrFirst() {
        return new OffsetPageRequest(Math.max(0, from - size), size, sort);
    }

    @Override
    public Pageable first() {
        return new OffsetPageRequest(0, size, sort);
    }

    @Override
    public Pageable withPage(int pageNumber) {
        Assert.isTrue(pageNumber >= 0, "Номер страницы не должен быть отрицательным");
        return new OffsetPageRequest(Math.multiplyExact(pageNumber, size), size, sort);
    }

    @Override
    public boolean hasPrevious() {
        return from > 0;
    }
}
