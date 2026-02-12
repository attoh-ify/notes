package com.example.notes.services.impl;

import com.example.notes.dto.ot.Delta;
import com.example.notes.dto.ot.TextOperation;
import com.example.notes.entities.note.Note;
import com.example.notes.entities.noteVersion.NoteVersion;
import com.example.notes.notifier.OperationRelayer;
import com.example.notes.repositories.NoteRepository;
import com.example.notes.repositories.NoteVersionRepository;
import com.example.notes.dto.enqueue.OperationQueueInPayload;
import com.example.notes.services.OperationQueueService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;

@Service
public class OperationQueueServiceImpl implements OperationQueueService {
    private final NotePolicyService notePolicyService;
    private final NoteVersionRepository noteVersionRepository;
    private final NoteRepository noteRepository;

    @Autowired
    private OperationRelayer operationRelayer;

    public OperationQueueServiceImpl(NotePolicyService notePolicyService, NoteVersionRepository noteVersionRepository, NoteRepository noteRepository) {
        this.notePolicyService = notePolicyService;
        this.noteVersionRepository = noteVersionRepository;
        this.noteRepository = noteRepository;
    }

    @Override
    public synchronized void enqueue(OperationQueueInPayload message) {
        Note note = notePolicyService.findNoteById(message.getNoteId());
        NoteVersion noteVersion = notePolicyService.findNoteVersionByNoteId(message.getNoteId());

        int serverRevision = noteVersion.getRevision();
        int clientRevision = message.getRevision();

        Delta transformedDelta = message.getDelta();

        if (clientRevision < serverRevision) {
            for (int i = clientRevision; i < serverRevision; i++) {
                TextOperation textOperation = note.getRevisionLog().get(i);

                boolean priority = message.getDelta().getActorId().compareTo(textOperation.getActorId()) > 0;
                transformedDelta = transformedDelta.transform(textOperation.getDelta(), priority);
            }
        }

        TextOperation newTextOperation = new TextOperation(
                transformedDelta,
                message.getFrom(),
                message.getRevision()
        );

        operationRelayer.relay(message.getNoteId(), newTextOperation);

        // update master delta

        // update revision log
        if (note.getRevisionLog() == null) {
            note.setRevisionLog(new ArrayList<>());
        }
        note.getRevisionLog().add(newTextOperation);
        noteRepository.save(note);

        // update current revision
        noteVersion.setRevision(serverRevision + 1);
        noteVersionRepository.save(noteVersion);
    }
}
