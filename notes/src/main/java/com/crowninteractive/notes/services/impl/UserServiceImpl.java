package com.crowninteractive.notes.services.impl;

import com.crowninteractive.notes.dto.user.LoginDto;
import com.crowninteractive.notes.dto.user.LoginResponseDto;
import com.crowninteractive.notes.dto.user.UserDto;
import com.crowninteractive.notes.entities.user.User;
import com.crowninteractive.notes.entities.user.UserPrincipal;
import com.crowninteractive.notes.exceptions.BadRequestException;
import com.crowninteractive.notes.mappers.UserMapper;
import com.crowninteractive.notes.repositories.UserRepository;
import com.crowninteractive.notes.services.EmailService;
import com.crowninteractive.notes.services.JwtService;
import com.crowninteractive.notes.services.UserService;
import com.crowninteractive.notes.utils.Helpers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class UserServiceImpl implements UserService {
    private final UserRepository userRepository;
    private final JwtService jwtService;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(12);
    private final AuthenticationManager authenticationManager;
    private final UserPolicyService userPolicyService;
    private final UserMapper userMapper;
    private final EmailService emailService;

    private static final Logger log =
            LoggerFactory.getLogger(UserServiceImpl.class);

    public UserServiceImpl(UserRepository userRepository, JwtService jwtService, AuthenticationManager authenticationManager, UserPolicyService userPolicyService, UserMapper userMapper, EmailService emailService) {
        this.userRepository = userRepository;
        this.jwtService = jwtService;
        this.authenticationManager = authenticationManager;
        this.userPolicyService = userPolicyService;
        this.userMapper = userMapper;
        this.emailService = emailService;
    }

    @Override
    public UserDto registerUser(UserDto user) {
        log.info("Registering user email={}", user.email());

        validateUser(user);
        User saved = userRepository.save(
                new User(
                        null,
                        UUID.randomUUID().toString(),
                        user.email(),
                        encoder.encode(user.password()),
                        null
                )
        );
        emailService.sendRegisterEmail(user.email());

        log.info("User registered successfully userId={} email={}",
                saved.getId(), saved.getEmail());

        return userMapper.toDto(saved);
    }

    @Override
    public UserDto getUserDetails(String email) {
        log.debug("Fetching user details email={}", email);

        return userMapper.toDto(userPolicyService.userExists(email));
    }

    @Override
    @Transactional
    public LoginResponseDto loginUser(LoginDto user) {
        log.info("Login attempt email={}", user.email());

        Authentication authentication =
                authenticationManager.authenticate(
                        new UsernamePasswordAuthenticationToken(
                                user.email(), user.password()
                        )
                );

        if (authentication.isAuthenticated()) {
            log.info("Authentication successful email={}", user.email());
            UserPrincipal principal = (UserPrincipal) authentication.getPrincipal();
            String token = jwtService.generateToken(user.email(), principal.getUserId());
            return new LoginResponseDto(token, principal.getUserId());
        }

        log.warn("Authentication failed email={}", user.email());
        throw new BadRequestException("Invalid username or password.");
    }

    private void validateUser(UserDto user) {
        log.debug("Validating user registration email={}", user.email());

        if (user.id() != null)
            throw new BadRequestException("User ID is system generated");
        if (Helpers.isBlank(user.email()))
            throw new BadRequestException("Email required");
        if (Helpers.isBlank(user.password()))
            throw new BadRequestException("Password required");

        userRepository.findByEmail(user.email()).ifPresent(existing -> {
            log.warn("Duplicate user registration email={}", user.email());
            throw new BadRequestException(
                    "This email is already registered to a User."
            );
        });
    }
}
