package com.crowninteractive.notes.notifier;

import com.crowninteractive.notes.dto.ot.TextOperation;
import com.crowninteractive.notes.listeners.MessagePusher;
import com.crowninteractive.notes.dto.message_payload.MessageType;
import org.springframework.beans.factory.annotation.Autowired;

public class OperationRelayer {
    @Autowired
    public MessagePusher messageRelayer;

    public void relay(String noteId, TextOperation outPayload) {
        messageRelayer.push(MessageType.OPERATION, noteId, outPayload);
    }
}
