package com.crowninteractive.notes.notifier;

import com.crowninteractive.notes.dto.message_payload.CollaboratorsPayload;
import com.crowninteractive.notes.listeners.MessagePusher;
import com.crowninteractive.notes.dto.message_payload.MessageType;
import org.springframework.beans.factory.annotation.Autowired;

public class CollaboratorCountNotifier {
    @Autowired
    public MessagePusher messagePusher;

    public void notifyCount(String noteId, CollaboratorsPayload collaborators) {
        messagePusher.push(MessageType.COLLABORATOR_JOIN, noteId, collaborators);
    }
}
