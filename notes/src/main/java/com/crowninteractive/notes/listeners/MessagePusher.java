package com.crowninteractive.notes.listeners;

import com.crowninteractive.notes.dto.message_payload.MessageOutPayloadWrapper;
import com.crowninteractive.notes.dto.message_payload.MessageType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.messaging.simp.SimpMessagingTemplate;

@Component
public class MessagePusher {
    @Autowired
    private SimpMessagingTemplate simpMessagingTemplate;

    public void push(MessageType type, String noteId, Object payload) {
        simpMessagingTemplate.convertAndSend(
                "/topic/note/" + noteId,
                new MessageOutPayloadWrapper<>(type, payload)
        );
    }
}
