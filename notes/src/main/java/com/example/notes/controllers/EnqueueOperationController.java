package com.example.notes.controllers;

import com.example.notes.dto.enqueue.EnqueueOperationPayload;
import com.example.notes.dto.enqueue.EnqueueOperationResponse;
import com.example.notes.shared.document_store.DocumentStore;
import com.example.notes.dto.note.DocumentModel;
import com.example.notes.dto.enqueue.OperationQueueInPayload;
import com.example.notes.shared.operation_queue.OperationQueue;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/enqueue")
public class EnqueueOperationController {
    @Autowired
    private DocumentStore documentStore;

    @Autowired
    private OperationQueue operationQueue;

    @PostMapping("/{docId}")
    private EnqueueOperationResponse enqueue(@PathVariable String docId, @RequestBody EnqueueOperationPayload operation) throws Exception {
        DocumentModel doc = documentStore.getDocumentFromDocId(docId);
        if (doc == null) {
            return new EnqueueOperationResponse("error", "document with id = " + docId + " does not exist");
        } else {
            operationQueue.enqueue(new OperationQueueInPayload(
                    docId,
                    operation.getRevision(),
                    operation.getFrom(),
                    operation.getOperation()
            ));

            return new EnqueueOperationResponse("ok", null);
        }
    }
}
