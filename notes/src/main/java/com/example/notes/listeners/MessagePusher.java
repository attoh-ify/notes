package com.example.notes.listeners;

import com.example.notes.dto.message_payload.MessageOutPayloadWrapper;
import com.example.notes.entities.MessageType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.messaging.simp.SimpMessagingTemplate;

@Component
public class MessagePusher {
    @Autowired
    private SimpMessagingTemplate simpMessagingTemplate;

    public void push(MessageType type, String docId, Object payload) {
        simpMessagingTemplate.convertAndSend(
                "/topic/doc/" + docId,
                new MessageOutPayloadWrapper<>(type, payload)
        );
    }
}
