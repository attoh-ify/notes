package com.example.notes.notifier;

import com.example.notes.listeners.MessagePusher;
import com.example.notes.entities.MessageType;
import com.example.notes.dto.enqueue.OperationQueueOutPayload;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

public class OperationRelayer {
    @Autowired
    public MessagePusher messageRelayer;

    public void relay(UUID noteId, OperationQueueOutPayload outPayload) {
        messageRelayer.push(MessageType.OPERATION, noteId, outPayload);
    }
}
