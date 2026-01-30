package com.example.notes.services;

import com.example.notes.dto.user.LoginDto;
import com.example.notes.dto.user.LoginResponseDto;
import com.example.notes.dto.user.UserDto;

public interface UserService {
    UserDto registerUser(UserDto user);
    UserDto getUserDetails(String email);
    LoginResponseDto loginUser(LoginDto user);
}
