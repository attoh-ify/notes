package com.crowninteractive.notes.controllers;

import com.crowninteractive.notes.dto.response.ResponseDto;
import com.crowninteractive.notes.dto.user.LoginDto;
import com.crowninteractive.notes.dto.user.LoginResponseDto;
import com.crowninteractive.notes.dto.user.UserDto;
import com.crowninteractive.notes.entities.user.UserPrincipal;
import com.crowninteractive.notes.security.CurrentUser;
import com.crowninteractive.notes.services.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletResponse;
import javax.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@Validated
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
            @Valid
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
            @Valid
            @RequestBody LoginDto dto,
            HttpServletResponse response
    ) {
        LoginResponseDto result = userService.loginUser(dto);

        Cookie cookie = new Cookie("access_token", result.getToken());
        cookie.setHttpOnly(true);
        cookie.setSecure(true);
        cookie.setPath("/");
        cookie.setMaxAge(4 * 60 * 60);
        response.setHeader("Set-Cookie", "access_token=" + result.getToken() + "; Path=/; HttpOnly; Max-Age=14400; SameSite=None; Secure");

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

    @PostMapping("/logout")
    @Operation(summary = "Logout user", description = "Logs out the user by clearing authentication cookies")
    public ResponseEntity<ResponseDto> logoutUser(HttpServletResponse response) {
        Cookie cookie = new Cookie("access_token", null);
        cookie.setHttpOnly(true);
        cookie.setSecure(false);
        cookie.setPath("/");
        cookie.setMaxAge(0);
        response.addCookie(cookie);
        response.setHeader(
                "Set-Cookie",
                "access_token=; Path=/; HttpOnly; Max-Age=0; SameSite=Lax"
        );

        return ResponseEntity.ok(
                new ResponseDto("User logged out successfully", null)
        );
    }
}
