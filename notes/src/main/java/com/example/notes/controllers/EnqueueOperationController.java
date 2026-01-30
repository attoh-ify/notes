package com.example.notes.controllers;

import com.example.notes.dto.enqueue.EnqueueOperationPayload;
import com.example.notes.dto.response.ResponseDto;
import com.example.notes.shared.document_store.NoteStore;
import com.example.notes.dto.note.DocumentModel;
import com.example.notes.dto.enqueue.OperationQueueInPayload;
import com.example.notes.shared.operation_queue.OperationQueue;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/notes/enqueue")
public class EnqueueOperationController {
    private final NoteStore noteStore;
    private final OperationQueue operationQueue;

    public EnqueueOperationController(NoteStore noteStore, OperationQueue operationQueue) {
        this.noteStore = noteStore;
        this.operationQueue = operationQueue;
    }

    @PostMapping("/{noteId}")
    public ResponseDto enqueue(@PathVariable UUID noteId, @RequestBody EnqueueOperationPayload operation) throws Exception {
        DocumentModel doc = noteStore.getNoteFromNoteId(noteId);
        if (doc == null) {
            return new ResponseDto(false, "Note with id = " + noteId + " does not exist");
        } else {
            operationQueue.enqueue(new OperationQueueInPayload(
                    noteId,
                    operation.getRevision(),
                    operation.getFrom(),
                    operation.getOperation()
            ));

            return new ResponseDto("ok");
        }
    }
}
