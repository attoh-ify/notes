package com.example.notes.config.websocket;

import com.example.notes.dto.message_payload.CollaboratorsPayload;
import com.example.notes.dto.message_payload.ReviewInProgressResponsePayload;
import com.example.notes.dto.note.NoteDto;
import com.example.notes.notifier.CollaboratorCountNotifier;
import com.example.notes.notifier.ReviewInProgressNotifier;
import com.example.notes.services.RedisService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationListener;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.security.Principal;
import java.util.Map;
import java.util.UUID;

@Component
public class SessionDisconnectEventListener implements ApplicationListener<SessionDisconnectEvent> {
    private static final Logger log = LoggerFactory.getLogger(SessionDisconnectEventListener.class);
    private final RedisService redisService;

    @Autowired
    public CollaboratorCountNotifier collaboratorCountNotifier;

    @Autowired
    private ReviewInProgressNotifier reviewInProgressNotifier;

    public SessionDisconnectEventListener(RedisService redisService) {
        this.redisService = redisService;
    }

    @Override
    public void onApplicationEvent(SessionDisconnectEvent event) {

        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        Map<String, Object> sessionAttributes = accessor.getSessionAttributes();

        if (sessionAttributes != null && sessionAttributes.containsKey("userId")) {
            Principal principal = accessor.getUser();

            if (principal == null) {
                log.warn("Unauthenticated principal");
                throw new IllegalStateException("Unauthenticated principal");
            }

            String userEmail = principal.getName();
            UUID noteId = UUID.fromString((String) sessionAttributes.get("noteId"));

            redisService.removeCollaboratorFromNote(noteId, userEmail);
            NoteDto note = redisService.getNote(noteId);
            Map<Object, Object> collaborators = redisService.getCollaborators(noteId);

            if (redisService.isReviewInProgress(noteId, userEmail)) {
                redisService.setReviewInProgress(noteId, userEmail, "false");
                reviewInProgressNotifier.notifyReviewInProgress(noteId, new ReviewInProgressResponsePayload(noteId, false));
            }

            if (note != null) {
                if (!collaborators.isEmpty()) {
                    collaboratorCountNotifier.notifyCount(
                            noteId,
                            new CollaboratorsPayload(collaborators)
                    );
                } else {
                    redisService.deleteNote(noteId);
                }
            }
        }
    }
}
