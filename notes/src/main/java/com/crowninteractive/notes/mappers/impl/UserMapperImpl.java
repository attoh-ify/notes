package com.crowninteractive.notes.mappers.impl;

import com.crowninteractive.notes.dto.user.UserDto;
import com.crowninteractive.notes.entities.user.User;
import com.crowninteractive.notes.mappers.NoteMapper;
import com.crowninteractive.notes.mappers.UserMapper;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.stream.Collectors;

@Component
public class UserMapperImpl implements UserMapper {
    private final NoteMapper noteMapper;

    public UserMapperImpl(NoteMapper noteMapper) {
        this.noteMapper = noteMapper;
    }

    @Override
    public User fromDto(UserDto userDto) {
        return new User(
                userDto.getId(),
                userDto.getUserId(),
                userDto.getEmail(),
                userDto.getPassword(),
                Optional.ofNullable(userDto.getNotes())
                                .map(notes -> notes.stream()
                                        .map(noteMapper::fromDto)
                                        .collect(Collectors.toList())
                                ).orElse(null)
        );
    }

    @Override
    public UserDto toDto(User user) {
        return new UserDto(
                user.getId(),
                user.getUserId(),
                user.getEmail(),
                user.getPassword(),
                Optional.ofNullable(user.getNotes())
                                .map(notes -> notes.stream()
                                        .map(note -> noteMapper.toDto(note, user.getEmail()))
                                        .collect(Collectors.toList())
                                ).orElse(null),
                user.getCreatedAt(),
                user.getUpdatedAt()
        );
    }
}
