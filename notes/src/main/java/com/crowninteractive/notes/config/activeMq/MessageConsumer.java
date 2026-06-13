package com.crowninteractive.notes.config.activeMq;

import com.crowninteractive.notes.dto.enqueue.OperationQueueInPayload;
import com.crowninteractive.notes.services.OperationQueueService;
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
        try {
            operationQueueService.enqueue(payload);
        } catch (Exception e) {
            log.error(
                    "Failed to process note operation. noteId={} opId={} from={} revision={}",
                    payload != null ? payload.getNoteId() : null,
                    payload != null ? payload.getOpId() : null,
                    payload != null ? payload.getFrom() : null,
                    payload != null ? payload.getRevision() : null,
                    e
            );

            throw e;
        }
    }
}
