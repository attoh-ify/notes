package com.example.notes.services.impl;

import com.example.notes.entities.user.User;
import com.example.notes.exceptions.BadRequestException;
import com.example.notes.repositories.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class UserPolicyService {
    private final UserRepository userRepository;

    private static final Logger log =
            LoggerFactory.getLogger(UserPolicyService.class);

    public UserPolicyService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public User userExists(String userEmail) {
        return userRepository.findByEmail(userEmail)
                .orElseThrow(() -> {
                    log.warn("User not found with email={}", userEmail);
                    return new BadRequestException("User not found with email");
                });
    }
}
