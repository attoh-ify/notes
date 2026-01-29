package com.example.notes.dto.enqueue;

import com.example.notes.dto.ot.TextOperation;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Setter
@Getter
public class OperationQueueInPayload {
    private UUID noteId;
    private int revision;
    private UUID from;
    private TextOperation operation;

    public  OperationQueueInPayload(UUID noteId, int revision, UUID from, TextOperation operation) {
        this.noteId = noteId;
        this.revision = revision;
        this.from = from;
        this.operation = operation;
    }

    public OperationQueueInPayload() {}

    @Override
    public String toString() {
        return "OperationQueueInPayload{" +
                "noteId='" + noteId + '\'' +
                ", revision=" + revision +
                ", from='" + from + '\'' +
                ", operation=" + operation +
                '}';
    }
}
