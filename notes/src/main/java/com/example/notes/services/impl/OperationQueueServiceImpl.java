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
    private static final Logger log = LoggerFactory.getLogger(OperationQueueServiceImpl.class);
    private final RedisService redisService;
    private static final long OPERATION_LOCK_TTL_SECONDS = 60;

    @Autowired
    private OperationRelayer operationRelayer;

    public OperationQueueServiceImpl(RedisService redisService) {
        this.redisService = redisService;
    }

    @Override
    public void enqueue(OperationQueueInPayload message) {
        UUID noteId = message.getNoteId();
        String lockOwner = UUID.randomUUID().toString();

        boolean acquiredLock = redisService.tryAcquireOperationLock(
                noteId,
                lockOwner,
                OPERATION_LOCK_TTL_SECONDS
        );

        if (!acquiredLock) {
            log.warn(
                    "Could not acquire operation lock. noteId={} opId={} from={} revision={}",
                    noteId,
                    message.getOpId(),
                    message.getFrom(),
                    message.getRevision()
            );

            throw new IllegalStateException(
                    "Could not acquire operation lock for noteId=" + noteId
            );
        }

        try {
            processEnqueuedOperation(message);
        } finally {
            redisService.releaseOperationLock(noteId, lockOwner);
        }
    }

    private void processEnqueuedOperation(OperationQueueInPayload message) {
        UUID noteId = message.getNoteId();

        NoteDto note = redisService.getNote(noteId);
        NoteVersionDto noteVersion = redisService.getNoteVersion(noteId);

        if (note == null || noteVersion == null) {
            log.error(
                    "Cannot process operation because note is not initialized in Redis. noteId={} opId={} from={} clientRevision={}",
                    noteId,
                    message.getOpId(),
                    message.getFrom(),
                    message.getRevision()
            );

            throw new IllegalStateException(
                    "Note is not initialized in Redis for noteId=" + noteId
            );
        }

        String opId = message.getOpId();

        if (opId == null || opId.isBlank()) {
            throw new IllegalArgumentException("Operation opId is required");
        }

        TextOperation alreadyProcessed = redisService.getProcessedOperation(noteId, opId);

        if (alreadyProcessed != null) {
            log.info(
                    "Duplicate operation received. noteId={} opId={} existingRevision={} from={}",
                    noteId,
                    opId,
                    alreadyProcessed.getRevision(),
                    message.getFrom()
            );

            operationRelayer.relay(noteId, alreadyProcessed);
            return;
        }

        Delta currentMasterDelta =
                QuillDeltaUtils.ensureTerminalNewline(noteVersion.masterDelta());

        int serverRevision = noteVersion.revision();
        int clientRevision = message.getRevision();

        if (clientRevision > serverRevision) {
            log.error(
                    "Client revision ahead of server. noteId={} opId={} clientRevision={} serverRevision={}",
                    noteId,
                    opId,
                    clientRevision,
                    serverRevision
            );

            throw new IllegalStateException("Client revision is ahead of server revision");
        }

        Delta transformedDelta = message.getDelta();

        if (clientRevision < serverRevision) {
            for (int i = clientRevision; i < serverRevision; i++) {
                int logIndex = i - redisService.getInitialRevision(noteId);

                if (logIndex < 0 || logIndex >= note.revisionLog().size()) {
                    log.error(
                            "Cannot transform operation because revision log is incomplete. noteId={} opId={} from={} clientRevision={} serverRevision={} requiredRevision={} logIndex={} logSize={} initialRevision={}",
                            noteId,
                            message.getOpId(),
                            message.getFrom(),
                            clientRevision,
                            serverRevision,
                            i,
                            logIndex,
                            note.revisionLog().size(),
                            redisService.getInitialRevision(noteId)
                    );

                    throw new IllegalStateException(
                            "Cannot transform operation because revision log is incomplete. " +
                                    "noteId=" + noteId +
                                    ", requiredRevision=" + i +
                                    ", clientRevision=" + clientRevision +
                                    ", serverRevision=" + serverRevision
                    );
                }

                TextOperation historyOp = note.revisionLog().get(logIndex);

                boolean serverHasOpPriority = serverHasPriority(message, historyOp);

                transformedDelta = historyOp.getDelta().transform(transformedDelta, serverHasOpPriority);
            }
        }

        TextOperation newTextOperation = new TextOperation(
                opId,
                transformedDelta,
                message.getFrom(),
                serverRevision + 1,
                OpState.PENDING,
                LocalDateTime.now()
        );

        note.revisionLog().add(newTextOperation);

        redisService.appendPendingHistoryOperation(noteId, newTextOperation);

        redisService.compactTransformRevisionLogIfNeeded(
                noteId,
                note
        );

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
        redisService.saveProcessedOperation(noteId, newTextOperation);
        operationRelayer.relay(noteId, newTextOperation);
    }

    private boolean serverHasPriority(OperationQueueInPayload incoming, TextOperation historyOp) {
        int actorCompare = incoming.getFrom().compareTo(historyOp.getActorEmail());

        if (actorCompare != 0) {
            return actorCompare > 0;
        }

        return incoming.getOpId().compareTo(historyOp.getOpId()) > 0;
    }
}
