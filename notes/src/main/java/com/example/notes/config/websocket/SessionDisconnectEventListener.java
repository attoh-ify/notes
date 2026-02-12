package com.example.notes.config.websocket;

import com.example.notes.dto.message_payload.CollaboratorsPayload;
import com.example.notes.entities.note.Note;
import com.example.notes.notifier.CollaboratorCountNotifier;
import com.example.notes.services.RedisService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationListener;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class SessionDisconnectEventListener implements ApplicationListener<SessionDisconnectEvent> {
    private static final Logger log = LoggerFactory.getLogger(SessionDisconnectEventListener.class);
    private final RedisService redisService;

    @Autowired
    public CollaboratorCountNotifier collaboratorCountNotifier;

    public SessionDisconnectEventListener(RedisService redisService) {
        this.redisService = redisService;
    }

    @Override
    public void onApplicationEvent(SessionDisconnectEvent event) {

        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        Map<String, Object> sessionAttributes = accessor.getSessionAttributes();

        if (sessionAttributes != null && sessionAttributes.containsKey("noteId")) {
            Principal principal = accessor.getUser();

            if (principal == null) {
                log.warn("Unauthenticated principal");
                throw new IllegalStateException("Unauthenticated principal");
            }

            String userEmail = principal.getName();
            UUID noteId = UUID.fromString((String) sessionAttributes.get("noteId"));

            redisService.removeCollaboratorFromNote(noteId, userEmail);
            Note note = redisService.getNote(noteId);
            List<String> collaborators = redisService.getCollaborators(noteId);

            if (note != null && !collaborators.isEmpty()) {
                collaboratorCountNotifier.notifyCount(
                        noteId,
                        new CollaboratorsPayload(collaborators)
                );
            }
        }
    }
}
