package com.example.notes.listeners;

import com.example.notes.dto.message_payload.MessageOutPayloadWrapper;
import com.example.notes.dto.message_payload.MessageType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.UUID;

@Component
public class MessagePusher {
    @Autowired
    private SimpMessagingTemplate simpMessagingTemplate;

    public void push(MessageType type, UUID noteId, Object payload) {
        simpMessagingTemplate.convertAndSend(
                "/topic/note/" + noteId,
                new MessageOutPayloadWrapper<>(type, payload)
        );
    }
}
