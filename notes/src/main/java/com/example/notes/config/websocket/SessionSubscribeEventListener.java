package com.example.notes.config.websocket;

import com.example.notes.dto.message_payload.CollaboratorsPayload;
import com.example.notes.notifier.CollaboratorCountNotifier;
import com.example.notes.services.RedisService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationListener;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionSubscribeEvent;

import java.security.Principal;
import java.util.Map;
import java.util.UUID;

@Component
public class SessionSubscribeEventListener implements ApplicationListener<SessionSubscribeEvent> {
    private static final Logger log =
            LoggerFactory.getLogger(SessionSubscribeEventListener.class);

    private final RedisService redisService;
    private final CollaboratorCountNotifier collaboratorCountNotifier;

    public SessionSubscribeEventListener(
            RedisService redisService,
            CollaboratorCountNotifier collaboratorCountNotifier
    ) {
        this.redisService = redisService;
        this.collaboratorCountNotifier = collaboratorCountNotifier;
    }

    @Override
    public void onApplicationEvent(SessionSubscribeEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());

        Principal principal = accessor.getUser();
        String destination = accessor.getDestination();

        if (principal == null || destination == null) {
            return;
        }

        if (!destination.startsWith("/topic/note/")) {
            return;
        }

        UUID noteId;

        try {
            noteId = UUID.fromString(destination.substring("/topic/note/".length()));
        } catch (Exception e) {
            log.warn("Invalid subscribe destination={}", destination);
            return;
        }

        Map<Object, Object> collaborators = redisService.getCollaborators(noteId);

        collaboratorCountNotifier.notifyCount(
                noteId,
                new CollaboratorsPayload(collaborators)
        );
    }
}