package com.example.notes.interceptors;

import com.example.notes.services.JwtService;
import com.example.notes.services.impl.MyUserDetailsService;
import com.example.notes.services.impl.NotePolicyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.core.userdetails.UserDetails;

import java.security.Principal;
import java.util.UUID;

public class StompAuthInterceptor implements ChannelInterceptor {
    private final NotePolicyService notePolicyService;
    private final JwtService jwtService;
    private final ApplicationContext context;

    private static final Logger log = LoggerFactory.getLogger(StompAuthInterceptor.class);

    public StompAuthInterceptor(NotePolicyService notePolicyService, JwtService jwtService, ApplicationContext context) {
        this.notePolicyService = notePolicyService;
        this.jwtService = jwtService;
        this.context = context;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);

        if (accessor == null) return message;
        System.out.println(accessor);

        StompCommand command = accessor.getCommand();

        if (StompCommand.CONNECT.equals(command)) {
            String authHeader = accessor.getFirstNativeHeader("Authorization");

            if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                log.warn("Authorization header not found");
                throw new IllegalStateException("Authorization header not found");
            }

            String token = authHeader.substring(7);

            String username = jwtService.extractUsername(token);
            UserDetails userDetails = context.getBean(MyUserDetailsService.class).loadUserByUsername(username);

            if (!jwtService.validateToken(token, userDetails)) {
                log.warn("Invalid token");
                throw new IllegalStateException("Invalid token");
            }

            Principal principal = () -> username;
            accessor.setUser(principal);
            log.info("WebSocket authenticated user={}", username);
        }

        if (StompCommand.SUBSCRIBE.equals(command)) {
            String destination = accessor.getDestination();
            Principal principal = accessor.getUser();

            if (destination == null || principal == null) {
                log.warn("Unauthenticated subscription attempt");
                throw new IllegalStateException("Unauthenticated subscription attempt");
            }

            if (destination.startsWith("/topic/public/")) {
                UUID noteId = extractNoteId(destination);
                String userEmail = principal.getName();

                log.debug("Authorizing SUBSCRIBE user={} noteId={}", userEmail, noteId);

                if (notePolicyService.validateEditor(userEmail, noteId) == null) {
                    log.warn("User does not have note access");
                    throw new IllegalStateException("User does not have note access");
                }
            }
        }

        if (StompCommand.SEND.equals(command)) {
            System.out.println("I got here!");
            System.out.println(accessor.getUser());
            if (accessor.getUser() == null) {
                log.warn("No user found");
                throw new IllegalStateException("No user found");
            }
        }
        return message;
    }

    private UUID extractNoteId(String destination) {
        try {
            String id = destination.substring("/topic/public/".length());
            return UUID.fromString(id);
        } catch (Exception e) {
            log.error("Invalid note topic destination");
            throw new IllegalArgumentException("Invalid note topic destination");
        }
    }
}
