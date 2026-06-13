package com.crowninteractive.notes.services;

import com.crowninteractive.notes.dto.enqueue.OperationQueueInPayload;

public interface OperationQueueService {
    void enqueue(OperationQueueInPayload inPayload);
}
