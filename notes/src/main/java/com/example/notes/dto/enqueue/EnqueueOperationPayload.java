package com.example.notes.dto.enqueue;

import com.example.notes.dto.ot.TextOperation;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
public class EnqueueOperationPayload {
    private TextOperation operation;
    private int revision;
    private UUID from;

    public EnqueueOperationPayload(TextOperation operation, int revision, UUID from) {
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
