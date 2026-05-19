package com.example.notes.services.impl;

import com.example.notes.dto.note.NoteDto;
import com.example.notes.dto.noteVersion.NoteVersionDto;
import com.example.notes.dto.ot.Delta;
import com.example.notes.dto.ot.OpState;
import com.example.notes.dto.ot.TextOperation;
import com.example.notes.notifier.OperationRelayer;
import com.example.notes.dto.enqueue.OperationQueueInPayload;
import com.example.notes.services.OperationQueueService;
import com.example.notes.services.RedisService;
import com.example.notes.utils.QuillDeltaUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
public class OperationQueueServiceImpl implements OperationQueueService {
    private final RedisService redisService;
    private static final Logger log =
            LoggerFactory.getLogger(OperationQueueServiceImpl.class);

    @Autowired
    private OperationRelayer operationRelayer;

    public OperationQueueServiceImpl(RedisService redisService) {
        this.redisService = redisService;
    }

    @Override
    public void enqueue(OperationQueueInPayload message) {
        UUID noteId = message.getNoteId();

        NoteDto note = redisService.getNote(noteId);
        NoteVersionDto noteVersion = redisService.getNoteVersion(noteId);

        Delta currentMasterDelta =
                QuillDeltaUtils.ensureTerminalNewline(noteVersion.masterDelta());

        int serverRevision = noteVersion.revision();
        int clientRevision = message.getRevision();

        Delta transformedDelta = message.getDelta();

        if (clientRevision < serverRevision) {
            for (int i = clientRevision; i < serverRevision; i++) {
                int logIndex = i - redisService.getInitialRevision(noteId);

                if (logIndex < 0 || logIndex >= note.revisionLog().size()) {
                    log.warn("logIndex {} out of bounds (size={}), skipping",
                            logIndex, note.revisionLog().size());
                    continue;
                }

                TextOperation historyOp = note.revisionLog().get(logIndex);

                if (historyOp.getActorEmail().equals(message.getFrom())) {
                    log.info("Same actor, skipping");
                    continue;
                }

                boolean serverHasOpPriority = message.getFrom().compareTo(historyOp.getActorEmail()) > 0;

                transformedDelta = historyOp.getDelta().transform(transformedDelta, serverHasOpPriority);
            }
        }

        TextOperation newTextOperation = new TextOperation(
                transformedDelta,
                message.getFrom(),
                serverRevision + 1,
                OpState.PENDING,
                LocalDateTime.now()
        );

        note.revisionLog().add(newTextOperation);

        NoteDto newRedisNote = new NoteDto(
                note.id(),
                note.ownerEmail(),
                note.title(),
                note.revisionLog(),
                note.visibility(),
                note.accessRole(),
                note.currentNoteVersionNumber(),
                note.createdAt(),
                note.updatedAt()
        );

        Delta newMasterDelta =
                QuillDeltaUtils.ensureTerminalNewline(
                        currentMasterDelta.compose(transformedDelta)
                );

        NoteVersionDto newRedisNoteVersion = new NoteVersionDto(
                noteVersion.id(),
                newMasterDelta,
                serverRevision + 1,
                noteVersion.comment(),
                noteVersion.versionNumber(),
                noteVersion.createdAt()
        );

        redisService.updateNote(newRedisNote, newRedisNoteVersion);
        operationRelayer.relay(noteId, newTextOperation);
    }
}
