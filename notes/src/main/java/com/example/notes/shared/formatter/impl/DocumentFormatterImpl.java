package com.example.notes.shared.formatter.impl;

import com.example.notes.shared.formatter.DocumentFormatter;
import com.example.notes.dto.ot.TextOperation;

public class DocumentFormatterImpl implements DocumentFormatter {
    private final StringBuffer buffer = new StringBuffer();

    @Override
    public void applyOperation(TextOperation operation) {
        switch (operation.getOpName()) {
            case INS -> applyInsert(operation);
            case DEL -> applyRemoveChar(operation);
        }
    }

    @Override
    public String getText() {
        return buffer.toString();
    }

    private void applyInsert(TextOperation operation) {
        if (buffer.length() == operation.getPosition()) {
            buffer.append(operation.getOperand());
        } else {
            buffer.insert(operation.getPosition(), operation.getOperand());
        }
    }

    private void applyRemoveChar(TextOperation operation) {
        buffer.deleteCharAt(operation.getPosition());
    }
}
