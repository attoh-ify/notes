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

@Service
public class OperationQueueServiceImpl implements OperationQueueService {
    private final RedisService redisService;

    @Autowired
    private OperationRelayer operationRelayer;

    public OperationQueueServiceImpl(RedisService redisService) {
        this.redisService = redisService;
    }

    @Override
    public synchronized void enqueue(OperationQueueInPayload message) {
        Note note = redisService.getNote(message.getNoteId());
        NoteVersion noteVersion = redisService.getNoteVersion(message.getNoteId());

        int serverRevision = noteVersion.getRevision();
        int clientRevision = message.getRevision();

        Delta transformedDelta = message.getDelta();

        if (clientRevision < serverRevision) {
            for (int i = clientRevision; i < serverRevision; i++) {
                TextOperation textOperation = note.getRevisionLog().get(i);

                boolean priority = message.getFrom().compareTo(textOperation.getActorId()) > 0;
                transformedDelta = transformedDelta.transform(textOperation.getDelta(), priority);
            }
        }

        TextOperation newTextOperation = new TextOperation(
                transformedDelta,
                message.getFrom(),
                noteVersion.getRevision() + 1
        );

        operationRelayer.relay(message.getNoteId(), newTextOperation);


        // update revision log
        if (note.getRevisionLog() == null) {
            note.setRevisionLog(new ArrayList<>());
        }
        note.getRevisionLog().add(newTextOperation);

        // update master delta
        Delta updateMaster = noteVersion.getMasterDelta().compose(transformedDelta);  // TODO: handle null;
        noteVersion.setMasterDelta(updateMaster);
        // update current revision
        noteVersion.setRevision(serverRevision + 1);

        redisService.updateNote(note, noteVersion);
    }
}
