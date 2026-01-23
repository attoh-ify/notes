package com.example.notes.shared.operation_transformations;

import com.example.notes.shared.model.TextOperation;

public interface OperationTransformations {
    TextOperation[] transform(TextOperation op1, TextOperation op2);
}
