package com.example.notes.feat_document.formatter.impl;

import com.example.notes.feat_document.formatter.DocumentFormatter;
import com.example.notes.shared.model.TextOperation;

public class CharSequenceDocumentFormatter implements DocumentFormatter {
    private final StringBuffer buffer = new StringBuffer();

    @Override
    public String applyOperation(TextOperation operation) {
        return switch (operation.getOpName()) {
            case "ins" -> applyInsert(operation);
            case "del" -> applyDelete(operation);
            default -> "";
        };
    }

    @Override
    public String getText() {
        return buffer.toString();
    }

    private String applyInsert(TextOperation operation) {
        if (buffer.length() == operation.getPosition()) {
            buffer.append(operation.getOperand());
        } else {
            buffer.insert(operation.getPosition(), operation.getOperand());
        }
        return buffer.toString();
    }

    private String applyDelete(TextOperation operation) {
        var start = operation.getPosition();
        var end = start + operation.getOperand().length();
        buffer.delete(start, end);
        return buffer.toString();
    }
}
