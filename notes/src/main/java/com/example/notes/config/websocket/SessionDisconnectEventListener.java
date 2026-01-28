package com.example.notes.config.websocket;

import com.example.notes.notifier.CollaboratorCountNotifier;
import com.example.notes.shared.document_store.DocumentStore;
import com.example.notes.dto.message_payload.CollaborationCountPayload;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

@Component
public class SessionDisconnectEventListener implements ApplicationListener<SessionDisconnectEvent> {
    @Autowired
    public DocumentStore documentStore;

    @Autowired
    public CollaboratorCountNotifier collaboratorCountNotifier;

    @Override
    public void onApplicationEvent(SessionDisconnectEvent event) {
        String userId = event.getUser().getName();
        var doc = documentStore.removeCollaboratorFromDocument(userId);
        if (doc.getCollaboratorCount() > 0) {
            collaboratorCountNotifier.notifyCount(
                    doc.getId(),
                    new CollaborationCountPayload(
                            doc.getCollaboratorCount()));
        }
    }
}
