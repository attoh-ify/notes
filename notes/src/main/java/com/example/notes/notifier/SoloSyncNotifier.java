package com.example.notes.notifier;

import com.example.notes.dto.message_payload.MessageType;
import com.example.notes.dto.message_payload.SoloSyncAckPayload;
import com.example.notes.listeners.MessagePusher;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

public class SoloSyncNotifier {
    @Autowired
    private MessagePusher messagePusher;

    public void notifySoloSyncAck(UUID noteId, SoloSyncAckPayload payload) {
        messagePusher.push(MessageType.SOLO_SYNC_ACK, noteId, payload);
    }
}