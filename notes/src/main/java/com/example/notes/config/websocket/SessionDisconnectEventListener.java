package com.example.notes.config.websocket;

import com.example.notes.notifier.CollaboratorCountNotifier;
import com.example.notes.shared.document_store.NoteStore;
import com.example.notes.dto.message_payload.CollaborationCountPayload;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationListener;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.util.Map;
import java.util.UUID;

@Component
public class SessionDisconnectEventListener implements ApplicationListener<SessionDisconnectEvent> {
    @Autowired
    public NoteStore NoteStore;

    @Autowired
    public CollaboratorCountNotifier collaboratorCountNotifier;

    @Override
    public void onApplicationEvent(SessionDisconnectEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        Map<String, Object> sessionAttributes = accessor.getSessionAttributes();

        if (sessionAttributes != null && sessionAttributes.containsKey("userId")) {
            UUID userId = UUID.fromString((String) sessionAttributes.get("userId"));

            var note = NoteStore.removeCollaboratorFromNote(userId);
            if (note != null && note.getCollaboratorCount() > 0) {
                collaboratorCountNotifier.notifyCount(
                        note.getId(),
                        new CollaborationCountPayload(note.getCollaboratorCount())
                );
            }
        }
    }
}
