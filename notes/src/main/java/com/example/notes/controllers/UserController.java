package com.example.notes.controllers;

import com.example.notes.dto.response.ResponseDto;
import com.example.notes.dto.user.LoginDto;
import com.example.notes.dto.user.LoginResponseDto;
import com.example.notes.dto.user.UserDto;
import com.example.notes.entities.user.UserPrincipal;
import com.example.notes.security.CurrentUser;
import com.example.notes.services.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/users")
@Tag(
        name = "Users",
        description = "User registration, authentication, and profile management"
)
public class UserController {
    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @PostMapping("/register")
    @Operation(
            summary = "Register a new user",
            description = "Creates a new user account with the provided registration details"
    )
    public ResponseDto registerUser(
            @RequestBody UserDto dto
    ) {
        return new ResponseDto("User registered", userService.registerUser(dto));
    }

    @PostMapping("/login")
    @Operation(
            summary = "Authenticate user",
            description = "Authenticates a user using email and password and returns an access token"
    )
    public ResponseEntity<ResponseDto> loginUser(
            @RequestBody LoginDto dto,
            HttpServletResponse response
    ) {
        LoginResponseDto result = userService.loginUser(dto);

        Cookie cookie = new Cookie("access_token", result.token());
        cookie.setHttpOnly(true);
        cookie.setSecure(false);
        cookie.setPath("/");
        cookie.setMaxAge(60 * 60 * 24);

        response.addCookie(cookie);

        return ResponseEntity.ok(
                new ResponseDto("User logged in", result)
        );
    }

    @GetMapping
    @Operation(
            summary = "Get user profile",
            description = "Retrieves user profile information using the user's email address"
    )
    public ResponseDto getDetails(
            @CurrentUser UserPrincipal currentUser
    ) {
        return new ResponseDto("User fetched", userService.getUserDetails(currentUser.getUsername()));
    }
}
