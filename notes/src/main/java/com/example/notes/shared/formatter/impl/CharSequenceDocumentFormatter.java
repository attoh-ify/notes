package com.example.notes.shared.formatter.impl;

import com.example.notes.shared.formatter.DocumentFormatter;
import com.example.notes.dto.ot.TextOperation;

public class CharSequenceDocumentFormatter implements DocumentFormatter {
    private final StringBuffer buffer = new StringBuffer();

    @Override
    public void applyOperation(TextOperation operation) {
        switch (operation.getOpName()) {
            case INS -> applyInsert(operation);
            case DEL -> applyDelete(operation);
        }
    }

    @Override
    public String getText() {
        return buffer.toString();
    }

    @Override
    public void setText(String text) {
        buffer.replace(0, buffer.length(), text);
    }

    private void applyInsert(TextOperation operation) {
        if (buffer.length() == operation.getPosition()) {
            buffer.append(operation.getOperand());
        } else {
            buffer.insert(operation.getPosition(), operation.getOperand());
        }
    }

    private void applyDelete(TextOperation operation) {
        var start = operation.getPosition();
        var end = start + operation.getOperand().length();
        buffer.delete(start, end);
    }
}
