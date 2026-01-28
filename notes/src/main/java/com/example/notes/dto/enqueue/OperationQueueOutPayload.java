package com.example.notes.dto.enqueue;

import com.example.notes.dto.ot.TextOperation;
import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
public class OperationQueueOutPayload {
    private String acknowledgeTo;
    private TextOperation operation;
    private long revision;

    public OperationQueueOutPayload(String acknowledgeTo, TextOperation operation, long revision) {
        this.acknowledgeTo = acknowledgeTo;
        this.operation = operation;
        this.revision = revision;
    }

    public OperationQueueOutPayload() {}

}
