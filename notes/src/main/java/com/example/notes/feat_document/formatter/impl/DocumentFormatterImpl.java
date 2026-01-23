package com.example.notes.feat_document.formatter.impl;

import com.example.notes.feat_document.formatter.DocumentFormatter;
import com.example.notes.shared.model.TextOperation;

public class DocumentFormatterImpl implements DocumentFormatter {
    private final StringBuffer buffer = new StringBuffer();

    @Override
    public String applyOperation(TextOperation operation) {
        return switch (operation.getOpName()) {
            case "ins" -> applyInsert(operation);
            case "del" -> applyRemoveChar(operation);
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

    private String applyRemoveChar(TextOperation operation) {
        buffer.deleteCharAt(operation.getPosition());
        return buffer.toString();
    }
}
