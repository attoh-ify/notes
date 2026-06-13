package com.crowninteractive.notes.config.activeMq;

import com.crowninteractive.notes.dto.enqueue.OperationQueueInPayload;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.stereotype.Component;

@Component
public class MessageProducer {
    @Autowired
    private JmsTemplate jmsTemplate;

    public void sendMessage(OperationQueueInPayload payload, String noteId) {
        jmsTemplate.convertAndSend("note-operations", payload, message -> {
            message.setStringProperty("noteId", noteId);
            message.setStringProperty("JMSXGroupID", noteId);
            return message;
        });
    }
}
