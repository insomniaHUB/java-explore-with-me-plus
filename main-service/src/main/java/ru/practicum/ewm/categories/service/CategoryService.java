package ru.practicum.ewm.categories.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.practicum.ewm.categories.dto.CategoryDto;
import ru.practicum.ewm.categories.dto.NewCategoryDto;
import ru.practicum.ewm.categories.mapper.CategoryMapper;
import ru.practicum.ewm.categories.model.Category;
import ru.practicum.ewm.categories.repository.CategoryRepository;
import ru.practicum.ewm.common.OffsetPageRequest;
import ru.practicum.ewm.events.repository.EventRepository;
import ru.practicum.ewm.exception.ConflictException;
import ru.practicum.ewm.exception.NotFoundException;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional
public class CategoryService {
    private final CategoryMapper categoryMapper;
    private final CategoryRepository categoryRepository;
    private final EventRepository eventRepository;

    public CategoryDto createCategory(NewCategoryDto newCategoryDto) {
        if (categoryRepository.existsByName(newCategoryDto.getName())) {
            throw new ConflictException("Категория с таким названием уже существует");
        }
        Category category = categoryMapper.toCategory(newCategoryDto);

        return categoryMapper.toCategoryDto(categoryRepository.save(category));
    }

    public CategoryDto updateCategory(Long catId, CategoryDto categoryDto) {
        Category category = categoryRepository.findById(catId)
                .orElseThrow(() -> new NotFoundException("Категория не найдена"));

        if (categoryRepository.existsByNameAndIdNot(categoryDto.getName(), catId)) {
            throw new ConflictException("Категория с таким названием уже существует");
        }

        category.setName(categoryDto.getName());
        return categoryMapper.toCategoryDto(categoryRepository.save(category));
    }

    public void deleteCategory(Long catId) {
        if (!categoryRepository.existsById(catId)) {
            throw new NotFoundException("Категория не найдена");
        }

        if (eventRepository.existsByCategoryId(catId)) {
            throw new ConflictException("Категория используется в событиях");
        }

        categoryRepository.deleteById(catId);
    }

    @Transactional(readOnly = true)
    public List<CategoryDto> getCategories(Integer from, Integer size) {
        Pageable pageRequest = new OffsetPageRequest(from, size);
        return categoryRepository.findAll(pageRequest).stream()
                .map(categoryMapper::toCategoryDto)
                .toList();
    }

    @Transactional(readOnly = true)
    public CategoryDto getCategory(Long catId) {
        return categoryMapper.toCategoryDto(categoryRepository.findById(catId)
                .orElseThrow(() -> new NotFoundException("Категория не найдена")));
    }
}
