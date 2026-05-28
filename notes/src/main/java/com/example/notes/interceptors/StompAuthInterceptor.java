package com.example.notes.interceptors;

import com.example.notes.services.JwtService;
import com.example.notes.services.RedisService;
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
import java.util.Map;
import java.util.UUID;

public class StompAuthInterceptor implements ChannelInterceptor {
    private final NotePolicyService notePolicyService;
    private final JwtService jwtService;
    private final ApplicationContext context;
    private final RedisService redisService;

    private static final Logger log = LoggerFactory.getLogger(StompAuthInterceptor.class);

    public StompAuthInterceptor(NotePolicyService notePolicyService, JwtService jwtService, ApplicationContext context, RedisService redisService) {
        this.notePolicyService = notePolicyService;
        this.jwtService = jwtService;
        this.context = context;
        this.redisService = redisService;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);

        if (accessor == null) return message;

        StompCommand command = accessor.getCommand();

        if (StompCommand.CONNECT.equals(command)) {
            String token = null;
            Map<String, Object> sessionAttributes = accessor.getSessionAttributes();

            if (sessionAttributes != null && sessionAttributes.containsKey("access_token")) {
                token = (String) sessionAttributes.get("access_token");
            }

            if (token == null) {
                String authHeader = accessor.getFirstNativeHeader("Authorization");

                if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                    log.warn("Authorization header not found");
                    throw new IllegalStateException("Authorization header not found");
                }

                token = authHeader.substring(7);
            }

            String username = jwtService.extractUsername(token);
            String userId = jwtService.extractUserId(token).toString();
            UserDetails userDetails = context.getBean(MyUserDetailsService.class).loadUserByUsername(username);

            if (!jwtService.validateToken(token, userDetails)) {
                log.warn("Invalid token");
                throw new IllegalStateException("Invalid token");
            }

            if (sessionAttributes != null) {
                sessionAttributes.put("userId", userId);
            }

            accessor.setUser(() -> username);
            log.info("WebSocket authenticated user={}", username);
        }

        if (StompCommand.SUBSCRIBE.equals(command)) {
            String destination = accessor.getDestination();
            Principal principal = accessor.getUser();

            if (principal == null) {
                throw new IllegalStateException("No user found");
            }

            if (destination == null) {
                throw new IllegalStateException("Missing SEND destination");
            }

            if (destination.startsWith("/topic/note/")) {
                UUID noteId = UUID.fromString(extractNoteId(destination));
                String userEmail = principal.getName();

                Map<String, Object> sessionAttributes = accessor.getSessionAttributes();
                if (sessionAttributes != null) {
                    sessionAttributes.put("noteId", extractNoteId(destination));
                }

                log.debug("Authorizing SUBSCRIBE user={} noteId={}", userEmail, noteId);

                notePolicyService.validateEditor(userEmail, noteId);

                String sessionId = accessor.getSessionId();

                redisService.addCollaboratorSession(noteId, userEmail, sessionId);

                return message;
            }

            if (destination.startsWith("/topic/")) {
                log.warn("Unauthorized topic subscription attempt. user={} destination={}",
                        principal.getName(),
                        destination
                );

                throw new IllegalStateException("Unauthorized topic subscription");
            }
        }

        if (StompCommand.SEND.equals(command)) {
            Principal principal = accessor.getUser();
            String destination = accessor.getDestination();

            if (principal == null) {
                throw new IllegalStateException("No user found");
            }

            if (destination == null) {
                throw new IllegalStateException("Missing SEND destination");
            }

            if (isNoteAppDestination(destination, "/operation") || isNoteAppDestination(destination, "/cursor")) {
                UUID noteId = extractNoteIdFromAppDestination(destination);

                Map<String, Object> sessionAttributes = accessor.getSessionAttributes();

                if (sessionAttributes == null) {
                    throw new IllegalStateException("Missing websocket session attributes");
                }

                Object joinedNoteId = sessionAttributes.get("noteId");

                if (joinedNoteId == null || !noteId.toString().equals(String.valueOf(joinedNoteId))) {
                    log.warn(
                            "Rejected operation SEND for note not joined. user={} destination={} joinedNoteId={}",
                            principal.getName(),
                            destination,
                            joinedNoteId
                    );

                    throw new IllegalStateException("Websocket session is not joined to this note");
                }

                return message;
            }

            if (destination.startsWith("/app/")) {
                log.warn(
                        "Unauthorized app SEND destination. user={} destination={}",
                        principal.getName(),
                        destination
                );

                throw new IllegalStateException("Unauthorized app destination");
            }
        }

        return message;
    }

    private String extractNoteId(String destination) {
        try {
            return destination.substring("/topic/note/".length());
        } catch (Exception e) {
            log.error("Invalid note topic destination");
            throw new IllegalArgumentException("Invalid note topic destination");
        }
    }

    private boolean isNoteAppDestination(String destination, String suffix) {
        return destination != null
                && destination.startsWith("/app/note/")
                && destination.endsWith(suffix);
    }

    private UUID extractNoteIdFromAppDestination(String destination) {
        try {
            String prefix = "/app/note/";
            String withoutPrefix = destination.substring(prefix.length());

            int slashIndex = withoutPrefix.indexOf('/');

            if (slashIndex < 0) {
                throw new IllegalArgumentException("Invalid note app destination");
            }

            String noteId = withoutPrefix.substring(0, slashIndex);

            return UUID.fromString(noteId);
        } catch (Exception e) {
            log.error("Invalid app destination={}", destination);
            throw new IllegalArgumentException("Invalid app destination");
        }
    }
}
