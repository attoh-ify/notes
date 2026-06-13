package com.crowninteractive.notes.notifier;

import com.crowninteractive.notes.dto.message_payload.MessageType;
import com.crowninteractive.notes.dto.message_payload.SoloSyncAckPayload;
import com.crowninteractive.notes.listeners.MessagePusher;
import org.springframework.beans.factory.annotation.Autowired;

public class SoloSyncNotifier {
    @Autowired
    private MessagePusher messagePusher;

    public void notifySoloSyncAck(String noteId, SoloSyncAckPayload payload) {
        messagePusher.push(MessageType.SOLO_SYNC_ACK, noteId, payload);
    }
}