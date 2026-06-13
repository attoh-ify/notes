package com.crowninteractive.notes.notifier;

import com.crowninteractive.notes.dto.message_payload.CursorPayload;
import com.crowninteractive.notes.dto.message_payload.MessageType;
import com.crowninteractive.notes.listeners.MessagePusher;
import org.springframework.beans.factory.annotation.Autowired;

public class CursorNotifier {
    @Autowired
    public MessagePusher messagePusher;

    public void notifyCursorChange(String noteId, CursorPayload cursorPayload) {
        messagePusher.push(MessageType.COLLABORATOR_CURSOR, noteId, cursorPayload);
    }
}
