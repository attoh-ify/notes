package com.example.notes.services;

import com.example.notes.dto.enqueue.OperationQueueInPayload;

public interface OperationQueueService {
    void enqueue(OperationQueueInPayload inPayload);
}
