package com.example.notes.dto.enqueue;

import com.example.notes.dto.ot.TextOperation;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Setter
@Getter
public class OperationQueueOutPayload {
    private UUID acknowledgeTo;
    private TextOperation operation;
    private long revision;

    public OperationQueueOutPayload(UUID acknowledgeTo, TextOperation operation, long revision) {
        this.acknowledgeTo = acknowledgeTo;
        this.operation = operation;
        this.revision = revision;
    }

    public OperationQueueOutPayload() {}

}
