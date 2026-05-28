package com.example.notes.config.activeMq;

import com.example.notes.dto.enqueue.OperationQueueInPayload;
import com.example.notes.services.OperationQueueService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.stereotype.Component;


@Component
public class MessageConsumer {
    private static final Logger log = LoggerFactory.getLogger(MessageConsumer.class);
    private final OperationQueueService operationQueueService;

    public MessageConsumer(OperationQueueService operationQueueService) {
        this.operationQueueService = operationQueueService;
    }

    @JmsListener(destination = "note-operations", concurrency = "2-8")
    public void receiveMessage(OperationQueueInPayload payload) {
        operationQueueService.enqueue(payload);
    }
}
