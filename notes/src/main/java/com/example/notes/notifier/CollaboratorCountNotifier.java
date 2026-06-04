package com.example.notes.notifier;

import com.example.notes.dto.message_payload.CollaboratorsPayload;
import com.example.notes.listeners.MessagePusher;
import com.example.notes.dto.message_payload.MessageType;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

public class CollaboratorCountNotifier {
    @Autowired
    public MessagePusher messagePusher;

    public void notifyCount(UUID noteId, CollaboratorsPayload collaborators) {
        messagePusher.push(MessageType.COLLABORATOR_JOIN, noteId, collaborators);
    }
}
