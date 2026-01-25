package com.example.notes.shared.model.message_out_payload;

import com.example.notes.shared.model.TextOperation;
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
