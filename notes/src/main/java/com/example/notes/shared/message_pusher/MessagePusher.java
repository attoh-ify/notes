package com.example.notes.shared.message_pusher;

import com.example.notes.shared.model.MessageOutPayloadWrapper;
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
