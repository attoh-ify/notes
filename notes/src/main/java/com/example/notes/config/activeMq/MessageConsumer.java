package com.example.notes.config.activeMq;

import com.example.notes.dto.enqueue.OperationQueueInPayload;
import com.example.notes.notifier.OperationRelayer;
import com.example.notes.services.OperationQueueService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.stereotype.Component;


@Component
public class MessageConsumer {
    private final OperationQueueService operationQueueService;
    @Autowired
    private OperationRelayer operationRelayer;

    public MessageConsumer(OperationQueueService operationQueueService) {
        this.operationQueueService = operationQueueService;
    }

    @JmsListener(destination = "note-operations", concurrency = "1")
    public void receiveMessage(OperationQueueInPayload payload) {
        operationQueueService.enqueue(payload);
    }
}
