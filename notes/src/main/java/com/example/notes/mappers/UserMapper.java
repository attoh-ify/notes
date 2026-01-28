package com.example.notes.mappers;

import com.example.notes.dto.user.UserDto;
import com.example.notes.entities.user.User;

public interface UserMapper {
    User fromDto(UserDto userDto);
    UserDto toDto(User user);
}
