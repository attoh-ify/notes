package com.example.notes.shared.operation_queue;

import com.example.notes.dto.enqueue.OperationQueueInPayload;

public interface OperationQueue {
    void enqueue(OperationQueueInPayload inPayload);
}
