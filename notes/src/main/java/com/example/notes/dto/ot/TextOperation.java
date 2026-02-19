package com.example.notes.dto.ot;

import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Setter
@Getter
public class TextOperation {
    private Delta delta;
    private UUID actorId;
    private int revision;

    public TextOperation() {}

    public TextOperation(
            Delta delta,
            UUID actorId,
            int revision
    ) {
        this.delta = delta;
        this.actorId = actorId;
        this.revision = revision;
    }

    @Override
    public String toString() {
        return "TextOperation{" +
                "delta='" + delta + '\'' +
                ", actorId='" + actorId + '\'' +
                ", revision=" + revision +
                '}';
    }
}
