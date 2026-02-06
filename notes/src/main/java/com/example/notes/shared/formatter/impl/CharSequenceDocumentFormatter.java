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
        int pos = operation.getPosition();
        int len = buffer.length();

        if (pos > len) {
            pos = len;
        } else if (pos < 0) {
            pos = 0;
        }

        buffer.insert(pos, operation.getOperand());
    }

    private void applyDelete(TextOperation operation) {
        int start = operation.getPosition();
        int end = start + operation.getOperand().length();
        int len = buffer.length();

        if (start >= len) return; // Nothing to delete
        if (end > len) end = len;

        buffer.delete(start, end);
    }
}
