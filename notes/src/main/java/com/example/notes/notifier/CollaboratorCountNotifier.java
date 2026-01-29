package com.example.notes.notifier;

import com.example.notes.listeners.MessagePusher;
import com.example.notes.entities.MessageType;
import com.example.notes.dto.message_payload.CollaborationCountPayload;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

public class CollaboratorCountNotifier {
    @Autowired
    public MessagePusher messagePusher;

    public void notifyCount(UUID noteId, CollaborationCountPayload collaborationCount) {
        messagePusher.push(MessageType.COLLABORATOR_COUNT, noteId, collaborationCount);
    }
}
