package com.crowninteractive.notes.config.websocket;

import com.crowninteractive.notes.dto.message_payload.CollaborationModePayload;
import com.crowninteractive.notes.dto.message_payload.CollaboratorsPayload;
import com.crowninteractive.notes.notifier.CollaborationModeNotifier;
import com.crowninteractive.notes.notifier.CollaboratorCountNotifier;
import com.crowninteractive.notes.services.RedisService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationListener;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionSubscribeEvent;

import java.security.Principal;
import java.util.Map;

@Component
public class SessionSubscribeEventListener implements ApplicationListener<SessionSubscribeEvent> {
    private static final Logger log = LoggerFactory.getLogger(SessionSubscribeEventListener.class);
    private final RedisService redisService;

    @Autowired
    public CollaboratorCountNotifier collaboratorCountNotifier;

    @Autowired
    public CollaborationModeNotifier collaborationModeNotifier;

    public SessionSubscribeEventListener(RedisService redisService) {
        this.redisService = redisService;
    }

    @Override
    public void onApplicationEvent(SessionSubscribeEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());

        Principal principal = accessor.getUser();
        String destination = accessor.getDestination();

        if (principal == null) {
            log.warn("Subscribe event had no authenticated principal. sessionId={}", accessor.getSessionId());
            return;
        }

        if (destination == null) {
            log.warn("Subscribe event had no authenticated destination. sessionId={}", accessor.getSessionId());
            return;
        }

        if (!destination.startsWith("/topic/note/")) {
            return;
        }

        String noteId;

        try {
            noteId = destination.substring("/topic/note/".length());
        } catch (Exception e) {
            log.warn("Invalid subscribe destination={}", destination);
            return;
        }

        Map<Object, Object> collaborators = redisService.getCollaborators(noteId);

        collaboratorCountNotifier.notifyCount(
                noteId,
                new CollaboratorsPayload(collaborators)
        );

        int activeSessionCount = redisService.getActiveSessionCount(noteId);

        collaborationModeNotifier.notifyMode(
                noteId,
                new CollaborationModePayload(
                        noteId,
                        redisService.getCollaborationMode(noteId),
                        activeSessionCount
                )
        );
    }
}