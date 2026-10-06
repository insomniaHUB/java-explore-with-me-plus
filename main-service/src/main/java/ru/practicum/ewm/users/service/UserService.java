package ru.practicum.ewm.users.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.practicum.ewm.exception.ConflictException;
import ru.practicum.ewm.exception.NotFoundException;
import ru.practicum.ewm.users.dto.NewUserRequest;
import ru.practicum.ewm.users.dto.UserDto;
import ru.practicum.ewm.users.mapper.UserMapper;
import ru.practicum.ewm.users.model.User;
import ru.practicum.ewm.users.repository.UserRepository;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional
public class UserService {
    private final UserRepository userRepository;
    private final UserMapper userMapper;

    public UserDto createUser(NewUserRequest userRequest) {
        isEmailUsed(userRequest.getEmail());
        User user = userMapper.toUserFromDto(userRequest);

        return userMapper.toUserDto(userRepository.save(user));
    }

    @Transactional(readOnly = true)
    public List<UserDto> getUsers(List<Long> ids, Integer from, Integer size) {
        PageRequest pageRequest = PageRequest.of(from / size, size);

        if (ids == null || ids.isEmpty()) {
            return userRepository.findAll(pageRequest).getContent().stream()
                    .map(userMapper::toUserDto)
                    .toList();
        } else {
            return userRepository.findByIdIn(ids, pageRequest).stream()
                    .map(userMapper::toUserDto)
                    .toList();
        }
    }

    public void deleteUser(Long userId) {
        if (!userRepository.existsById(userId)) {
            throw new NotFoundException("Пользователь не был найден");
        }
        userRepository.deleteById(userId);
    }

    private void isEmailUsed(String email) {
        User user = userRepository.findByEmail(email);

        if (user != null) {
            throw new ConflictException("Пользователь с такой почтой уже существует");
        }
    }
}
