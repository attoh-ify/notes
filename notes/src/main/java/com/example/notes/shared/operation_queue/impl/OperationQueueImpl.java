package com.example.notes.shared.operation_queue.impl;

import com.example.notes.notifier.OperationRelayer;
import com.example.notes.shared.document_store.NoteStore;
import com.example.notes.dto.note.DocumentModel;
import com.example.notes.dto.enqueue.OperationQueueInPayload;
import com.example.notes.dto.enqueue.OperationQueueOutPayload;
import com.example.notes.shared.operation_queue.OperationQueue;
import org.springframework.beans.factory.annotation.Autowired;

public class OperationQueueImpl implements OperationQueue {
    @Autowired
    private NoteStore noteStore;

    @Autowired
    private OperationRelayer operationRelayer;

    @Override
    public void enqueue(OperationQueueInPayload message) {
        DocumentModel doc = noteStore.getNoteFromNoteId(message.getNoteId());

        int serverDocRevision = doc.getRevision();
        int messageDocRevision = message.getRevision();

        if (messageDocRevision < serverDocRevision) {
            // client doc version is outdated
            // in this case, transform this message against all committed revisions after serverDocVersion
            var transformedOperations = doc.transformAgainstRevisionLogs(message.getOperation(), messageDocRevision);
            if (transformedOperations == null || transformedOperations.isEmpty()) {
                return;
            }

            for (var operation : transformedOperations) {
                if (operation == null) continue;
                operationRelayer.relay(message.getNoteId(), new OperationQueueOutPayload(
                        message.getFrom(),
                        operation,
                        doc.getRevision() + 1
                ));

                doc.applyOperation(operation);
            }
        } else if (messageDocRevision == serverDocRevision) {
            operationRelayer.relay(message.getNoteId(), new OperationQueueOutPayload(
                    message.getFrom(),
                    message.getOperation(),
                    doc.getRevision() + 1
            ));

            doc.applyOperation(message.getOperation());
        }
    }
}
