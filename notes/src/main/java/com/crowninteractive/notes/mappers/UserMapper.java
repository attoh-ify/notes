package com.crowninteractive.notes.mappers;

import com.crowninteractive.notes.dto.user.UserDto;
import com.crowninteractive.notes.entities.user.User;

public interface UserMapper {
    User fromDto(UserDto userDto);
    UserDto toDto(User user);
}
