package com.example.notes.shared.model;

import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
public class TextOperation {
    private OperationNameEnum opName;
    private String operand;
    private int position;

    public TextOperation() {}

    public TextOperation(
            OperationNameEnum opName,
            String operand,
            int position
    ) {
        this.opName = opName;
        this.operand = operand;
        this.position = position;
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
