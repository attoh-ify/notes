package com.example.notes.services.impl;

import com.example.notes.services.JwtService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

@Service
public class JwtServiceImpl implements JwtService {
    private final String secretKey;

    private static final Logger log =
            LoggerFactory.getLogger(JwtServiceImpl.class);

    public JwtServiceImpl(@Value("${JWT_SECRET}") String secretKey) {
        this.secretKey = secretKey;
        log.info("JWTService initialized with configured secret");
    }

    public String generateToken(String email, UUID userId) {
        log.info("Generating JWT token for email={}", email);

        Map<String, Object> claims = new HashMap<>();
        claims.put("id", userId);

        long timeMillis = System.currentTimeMillis();

        String token = Jwts.builder()
                .claims()
                .add(claims)
                .subject(email)
                .issuedAt(new Date(timeMillis))
                .expiration(new Date(timeMillis + 60 * 60 * 1000)) // 1 hour
                .and()
                .signWith(getKey())
                .compact();

        log.debug("JWT token generated successfully for email={}", email);
        return token;
    }

    private SecretKey getKey() {
        log.trace("Deriving signing key for JWT operations");
        byte[] keyBytes = Decoders.BASE64.decode(secretKey);
        return Keys.hmacShaKeyFor(keyBytes);
    }

    public String extractUsername(String token) {
        log.debug("Extracting username from JWT");
        return extractClaim(token, Claims::getSubject);
    }

    public UUID extractUserId(String token) {
        log.debug("Extracting user id from JWT");
        return UUID.fromString(
                extractClaim(token, claims -> claims.get("id", String.class))
        );
    }

    private <T> T extractClaim(String token, Function<Claims, T> claimResolver) {
        log.trace("Extracting claim from JWT");
        final Claims claims = extractAllClaims(token);
        return claimResolver.apply(claims);
    }

    private Claims extractAllClaims(String token) {
        log.trace("Parsing JWT claims");
        return Jwts.parser()
                .verifyWith(getKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public boolean validateToken(String token, UserDetails userDetails) {
        log.debug("Validating JWT token for user={}", userDetails.getUsername());

        final String userName = userDetails.getUsername();

        boolean valid = userName.equals(extractUsername(token))
                && !isTokenExpired(token);

        log.debug(
                "JWT validation completed user={} valid={}",
                userName,
                valid
        );

        return valid;
    }

    private boolean isTokenExpired(String token) {
        boolean expired = extractExpiration(token).before(new Date());
        log.trace("JWT expiration check expired={}", expired);
        return expired;
    }

    private Date extractExpiration(String token) {
        log.trace("Extracting JWT expiration timestamp");
        return extractClaim(token, Claims::getExpiration);
    }
}
