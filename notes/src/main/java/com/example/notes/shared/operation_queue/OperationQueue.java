package com.example.notes.shared.operation_queue;

import com.example.notes.shared.model.OperationQueueInPayload;

public interface OperationQueue {
    void enqueue(OperationQueueInPayload inPayload);
}
