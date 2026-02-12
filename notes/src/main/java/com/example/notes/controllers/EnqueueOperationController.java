package com.example.notes.controllers;

import com.example.notes.dto.enqueue.EnqueueOperationPayload;
import com.example.notes.dto.response.ResponseDto;
import com.example.notes.dto.enqueue.OperationQueueInPayload;
import com.example.notes.services.OperationQueueService;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/notes/enqueue")
public class EnqueueOperationController {
    private final OperationQueueService operationQueue;

    public EnqueueOperationController(OperationQueueService operationQueue) {
        this.operationQueue = operationQueue;
    }

    @PostMapping("/{noteId}")
    public ResponseDto enqueue(@PathVariable UUID noteId, @RequestBody EnqueueOperationPayload operation) throws Exception {
        operationQueue.enqueue(new OperationQueueInPayload(
                noteId,
                operation.getRevision(),
                operation.getFrom(),
                operation.getDelta()
        ));

        return new ResponseDto("ok");
    }
}
