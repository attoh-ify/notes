package com.example.notes.services;

import org.springframework.security.core.userdetails.UserDetails;

import java.util.UUID;

public interface JwtService {
    String generateToken(String email, UUID userId);
    String extractUsername(String token);
    UUID extractUserId(String token);
    boolean validateToken(String token, UserDetails userDetails);
}
