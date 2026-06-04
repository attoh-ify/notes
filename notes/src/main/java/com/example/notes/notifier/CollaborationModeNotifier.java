package com.example.notes.notifier;

import com.example.notes.dto.message_payload.CollaborationModePayload;
import com.example.notes.dto.message_payload.MessageType;
import com.example.notes.listeners.MessagePusher;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

public class CollaborationModeNotifier {
    @Autowired
    private MessagePusher messagePusher;

    public void notifyMode(UUID noteId, CollaborationModePayload payload) {
        messagePusher.push(MessageType.COLLABORATION_MODE, noteId, payload);
    }
}