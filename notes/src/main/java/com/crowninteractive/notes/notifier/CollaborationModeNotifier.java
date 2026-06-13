package com.crowninteractive.notes.notifier;

import com.crowninteractive.notes.dto.message_payload.CollaborationModePayload;
import com.crowninteractive.notes.dto.message_payload.MessageType;
import com.crowninteractive.notes.listeners.MessagePusher;
import org.springframework.beans.factory.annotation.Autowired;

public class CollaborationModeNotifier {
    @Autowired
    private MessagePusher messagePusher;

    public void notifyMode(String noteId, CollaborationModePayload payload) {
        messagePusher.push(MessageType.COLLABORATION_MODE, noteId, payload);
    }
}