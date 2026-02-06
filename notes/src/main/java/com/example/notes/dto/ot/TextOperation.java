package com.example.notes.dto.ot;

import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Setter
@Getter
public class TextOperation {
    private OperationNameEnum opName;
    private String operand;
    private int position;
    private UUID actorId;

    public TextOperation() {}

    public TextOperation(
            OperationNameEnum opName,
            String operand,
            int position,
            UUID actorId
    ) {
        this.opName = opName;
        this.operand = operand;
        this.position = position;
        this.actorId = actorId;
    }

    @Override
    public String toString() {
        return "TextOperation{" +
                "opName='" + opName + '\'' +
                ", operand='" + operand + '\'' +
                ", position=" + position +
                '}';
    }
}
