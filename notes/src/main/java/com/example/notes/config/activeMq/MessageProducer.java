package com.example.notes.config.activeMq;

import com.example.notes.dto.enqueue.OperationQueueInPayload;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class MessageProducer {
    @Autowired
    private JmsTemplate jmsTemplate;

    public void sendMessage(OperationQueueInPayload payload, UUID noteId) {
        jmsTemplate.convertAndSend("note-operations", payload, message -> {
            message.setStringProperty("noteId", noteId.toString());
            message.setStringProperty("JMSXGroupID", noteId.toString());
            return message;
        });
    }
}
