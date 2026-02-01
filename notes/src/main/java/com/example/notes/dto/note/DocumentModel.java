package com.example.notes.dto.note;

import com.example.notes.shared.formatter.DocumentFormatter;
import com.example.notes.dto.ot.TextOperation;
import com.example.notes.shared.operation_transformations.OperationTransformations;
import lombok.Getter;
import lombok.Setter;

import java.util.*;

public class DocumentModel {
    final List<TextOperation> revisionLog = new ArrayList<>();
    private final OperationTransformations operationTransformations;
    @Getter
    private final UUID id;
    private final DocumentFormatter documentFormatter;
    @Getter
    private int revision = 0;
    @Getter
    @Setter
    private int collaboratorCount = 0;

    public DocumentModel(UUID id, DocumentFormatter documentFormatter, OperationTransformations operationTransformations) {
        this.id = id;
        this.documentFormatter = documentFormatter;
        this.operationTransformations = operationTransformations;
    }

    public String getDocText() {
        return documentFormatter.getText();
    }

    public void applyOperation(TextOperation operation) {
        documentFormatter.applyOperation(operation);
        revisionLog.add(revision, operation);
        revision++;
    }

    public List<TextOperation> transformAgainstRevisionLogs(TextOperation operation, int from) {
        record TextOperationWrapper(TextOperation operation, int transformFrom) {}

        List<TextOperation> transformedOperations = new ArrayList<>();
        Queue<TextOperationWrapper> opQueue = new LinkedList<>();
        opQueue.add(new TextOperationWrapper(operation, from));

        while (!opQueue.isEmpty()) {
            var op = opQueue.poll();
            var transformedOperation = operation;

            for (int revision = op.transformFrom; revision < revisionLog.size(); revision++) {
                var operations = operationTransformations.transform(transformedOperation, revisionLog.get(revision));
                if (operations == null || operations.length == 0) {
                    transformedOperation = null;
                    break;
                }
                transformedOperation = operations[0];
                if (operations.length > 1) {
                    opQueue.add(new TextOperationWrapper(operations[1], revision + 1));
                }
            }

            transformedOperations.add(transformedOperation);
        }

        return transformedOperations;
    }

    public int decrementCollaboratorCount() {
        collaboratorCount--;
        return collaboratorCount;
    }
}
