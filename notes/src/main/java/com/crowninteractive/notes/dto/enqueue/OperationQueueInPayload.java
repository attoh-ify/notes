package com.crowninteractive.notes.dto.enqueue;

import com.crowninteractive.notes.dto.ot.Delta;
import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
public class OperationQueueInPayload {
    private String noteId;
    private String opId;
    private int revision;
    private String from;
    private Delta delta;

    public  OperationQueueInPayload(String noteId, String opId, int revision, String from, Delta delta) {
        this.noteId = noteId;
        this.opId = opId;
        this.revision = revision;
        this.from = from;
        this.delta = delta;
    }

    public OperationQueueInPayload() {}

    @Override
    public String toString() {
        return "OperationQueueInPayload{" +
                "noteId=" + noteId +
                ", opId='" + opId + '\'' +
                ", revision=" + revision +
                ", from=" + from +
                ", deltaOps=" + (delta != null && delta.ops != null ? delta.ops.size() : 0) +
                '}';
    }
}
