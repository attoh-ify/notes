package com.example.notes.dto.ot;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

@Setter
@Getter
public class TextOperation {
    private Delta delta;
    private UUID actorId;
    private int revision;
    private LocalDateTime createdAt;

    public TextOperation() {}

    public TextOperation(
            Delta delta,
            UUID actorId,
            int revision,
            LocalDateTime createdAt
    ) {
        this.delta = delta;
        this.actorId = actorId;
        this.revision = revision;
        this.createdAt = createdAt;
    }

    @Override
    public String toString() {
        return "TextOperation{" +
                "delta=" + delta +
                ", actorId=" + actorId +
                ", revision=" + revision +
                ", createdAt=" + createdAt +
                '}';
    }
}
