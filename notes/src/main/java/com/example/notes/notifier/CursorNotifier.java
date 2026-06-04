package com.example.notes.notifier;

import com.example.notes.dto.message_payload.CursorPayload;
import com.example.notes.dto.message_payload.MessageType;
import com.example.notes.listeners.MessagePusher;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

public class CursorNotifier {
    @Autowired
    public MessagePusher messagePusher;

    public void notifyCursorChange(UUID noteId, CursorPayload cursorPayload) {
        messagePusher.push(MessageType.COLLABORATOR_CURSOR, noteId, cursorPayload);
    }
}
