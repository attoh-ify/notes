package com.example.notes.services.impl;

import com.example.notes.dto.ot.Delta;
import com.example.notes.dto.ot.TextOperation;
import com.example.notes.entities.note.Note;
import com.example.notes.entities.noteVersion.NoteVersion;
import com.example.notes.notifier.OperationRelayer;
import com.example.notes.dto.enqueue.OperationQueueInPayload;
import com.example.notes.services.OperationQueueService;
import com.example.notes.services.RedisService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.UUID;

@Service
public class OperationQueueServiceImpl implements OperationQueueService {
    private final RedisService redisService;

    @Autowired
    private OperationRelayer operationRelayer;

    public OperationQueueServiceImpl(RedisService redisService) {
        this.redisService = redisService;
    }

    @Override
    public void enqueue(OperationQueueInPayload message) {
        UUID noteId = message.getNoteId();
        int retries = 0;
        int maxRetries = 100;

        while (!redisService.acquireLock(noteId)) {
            try {
                Thread.sleep(20);
                retries++;
                if (retries > maxRetries) {
                    throw new RuntimeException("Could not acquire lock for note: " + noteId);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }

        try {
            Note note = redisService.getNote(noteId);
            NoteVersion noteVersion = redisService.getNoteVersion(noteId);

            int serverRevision = noteVersion.getRevision();
            int clientRevision = message.getRevision();
            Delta transformedDelta = message.getDelta();

            if (clientRevision < serverRevision) {
                for (int i = clientRevision; i < serverRevision; i++) {
                    TextOperation historyOp = note.getRevisionLog().get(i);
                    boolean priority = message.getFrom().compareTo(historyOp.getActorId()) > 0;
                    transformedDelta = historyOp.getDelta().transform(transformedDelta, !priority);
                }
            }

            TextOperation newTextOperation = new TextOperation(
                    transformedDelta,
                    message.getFrom(),
                    serverRevision + 1
            );

            if (note.getRevisionLog() == null) note.setRevisionLog(new ArrayList<>());
            note.getRevisionLog().add(newTextOperation);

            Delta updatedMaster = noteVersion.getMasterDelta().compose(transformedDelta);
            noteVersion.setMasterDelta(updatedMaster);
            noteVersion.setRevision(serverRevision + 1);

            redisService.updateNote(note, noteVersion);
            operationRelayer.relay(noteId, newTextOperation);

        } finally {
            redisService.releaseLock(noteId);
        }
    }
}
