package com.example.notes.feat_enqueue_operation.model;

import com.example.notes.shared.model.TextOperation;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class EnqueueOperationPayload {
    private TextOperation operation;
    private int revision;
    private String from;

    public EnqueueOperationPayload(TextOperation operation, int revision, String from) {
        this.operation = operation;
        this.revision = revision;
        this.from = from;
    }

    public EnqueueOperationPayload() {}

    @Override
    public String toString() {
        return "EnqueueOperationPayload{" +
                "operation=" + operation +
                ", revision=" + revision +
                ", from='" + from + '\'' +
                '}';
    }
}
