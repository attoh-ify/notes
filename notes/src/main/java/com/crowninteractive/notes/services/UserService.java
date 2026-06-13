package com.crowninteractive.notes.services;

import com.crowninteractive.notes.dto.user.LoginDto;
import com.crowninteractive.notes.dto.user.LoginResponseDto;
import com.crowninteractive.notes.dto.user.UserDto;

public interface UserService {
    UserDto registerUser(UserDto user);
    UserDto getUserDetails(String email);
    LoginResponseDto loginUser(LoginDto user);
}
