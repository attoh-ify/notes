package com.crowninteractive.notes.services;

import org.springframework.security.core.userdetails.UserDetails;

public interface JwtService {
    String generateToken(String email, String userId);
    String extractUsername(String token);
    String extractUserId(String token);
    boolean validateToken(String token, UserDetails userDetails);
}
