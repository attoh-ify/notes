package com.example.notes.dto.enqueue;

import com.example.notes.dto.ot.TextOperation;
import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
public class OperationQueueInPayload {
    private String docId;
    private int revision;
    private String from;
    private TextOperation operation;

    public  OperationQueueInPayload(String docId, int revision, String from, TextOperation operation) {
        this.docId = docId;
        this.revision = revision;
        this.from = from;
        this.operation = operation;
    }

    public OperationQueueInPayload() {}

    @Override
    public String toString() {
        return "OperationQueueInPayload{" +
                "docId='" + docId + '\'' +
                ", revision=" + revision +
                ", from='" + from + '\'' +
                ", operation=" + operation +
                '}';
    }
}
