package com.example.notes.config.websocket;

import com.example.notes.dto.message_payload.CollaboratorsPayload;
import com.example.notes.dto.message_payload.ReviewInProgressResponsePayload;
import com.example.notes.dto.note.NoteDto;
import com.example.notes.notifier.CollaboratorCountNotifier;
import com.example.notes.notifier.ReviewInProgressNotifier;
import com.example.notes.services.NoteService;
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
    private final NoteService noteService;

    @Autowired
    public CollaboratorCountNotifier collaboratorCountNotifier;

    @Autowired
    private ReviewInProgressNotifier reviewInProgressNotifier;

    public SessionDisconnectEventListener(RedisService redisService, NoteService noteService) {
        this.redisService = redisService;
        this.noteService = noteService;
    }

    @Override
    public void onApplicationEvent(SessionDisconnectEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());

        Map<String, Object> sessionAttributes = accessor.getSessionAttributes();
        if (sessionAttributes == null) return;

        if (!sessionAttributes.containsKey("userId")) return;
        if (!sessionAttributes.containsKey("noteId")) return;

        Principal principal = accessor.getUser();

        if (principal == null) {
            log.warn("Disconnect event had no authenticated principal. sessionId={}", accessor.getSessionId());
            return;
        }

        String userEmail = principal.getName();
        UUID noteId = UUID.fromString((String) sessionAttributes.get("noteId"));
        String sessionId = accessor.getSessionId();

        boolean removedFinalUserSession =
                redisService.removeCollaboratorSession(noteId, sessionId);

        NoteDto note = redisService.getNote(noteId);
        Map<Object, Object> collaborators = redisService.getCollaborators(noteId);

        log.info(
                "Disconnect cleanup reached. noteId={} sessionId={} removedFinalUserSession={} collaborators={}",
                noteId,
                sessionId,
                removedFinalUserSession,
                collaborators
        );

        if (removedFinalUserSession && redisService.isReviewInProgress(noteId, userEmail)) {
            redisService.setReviewInProgress(noteId, userEmail, "false");
            reviewInProgressNotifier.notifyReviewInProgress(
                    noteId,
                    new ReviewInProgressResponsePayload(noteId, false)
            );
        }

        if (note != null) {
            if (!collaborators.isEmpty()) {
                collaboratorCountNotifier.notifyCount(
                        noteId,
                        new CollaboratorsPayload(collaborators)
                );
            } else {
                log.info("Final collaborator left. Saving then deleting Redis note. noteId={}", noteId);
                try {
                    noteService.saveNote(userEmail, noteId);
                    log.info("Final disconnect save succeeded. Deleting Redis note. noteId={}", noteId);
                } catch (Exception e) {
                    log.warn(
                            "Could not save note on final websocket disconnect. It may already be deleted. noteId={} user={}",
                            noteId,
                            userEmail,
                            e
                    );
                } finally {
                    redisService.deleteNote(noteId);
                }
            }
        }
    }
}
