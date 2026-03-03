package com.example.notes.services.impl;

import com.example.notes.dto.ot.Delta;
import com.example.notes.dto.ot.TextOperation;
import com.example.notes.entities.note.Note;
import com.example.notes.entities.noteVersion.NoteVersion;
import com.example.notes.notifier.OperationRelayer;
import com.example.notes.dto.enqueue.OperationQueueInPayload;
import com.example.notes.services.OperationQueueService;
import com.example.notes.services.RedisService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
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

        Note note = redisService.getNote(noteId);
        NoteVersion noteVersion = redisService.getNoteVersion(noteId);

        int serverRevision = noteVersion.getRevision();
        int clientRevision = message.getRevision();

        log.info("=== ENQUEUE ===");
        log.info("actor:           {}", message.getFrom());
        log.info("clientRevision:  {}", clientRevision);
        log.info("serverRevision:  {}", serverRevision);
        log.info("revisionLog size:{}", note.getRevisionLog() == null ? 0 : note.getRevisionLog().size());
        log.info("incomingDelta:   {}", message.getDelta().toString());

        Delta transformedDelta = message.getDelta();

        if (clientRevision < serverRevision) {
            for (int i = clientRevision; i < serverRevision; i++) {
                int logIndex = i - redisService.getInitialRevision(noteId);

                if (logIndex < 0 || logIndex >= note.getRevisionLog().size()) {
                    log.warn("logIndex {} out of bounds (size={}), skipping",
                            logIndex, note.getRevisionLog().size());
                    continue;
                }

                TextOperation historyOp = note.getRevisionLog().get(logIndex);
                log.info("  historyOp actor: {}", historyOp.getActorId());
                log.info("  historyOp delta: {}", historyOp.getDelta().ops);

                if (historyOp.getActorId().equals(message.getFrom())) {
                    log.info("  same actor, skipping");
                    continue;
                }

                boolean serverHasOpPriority = message.getFrom().compareTo(historyOp.getActorId()) > 0;
                log.info("  priority (server wins): {}", serverHasOpPriority);

                transformedDelta = historyOp.getDelta().transform(transformedDelta, serverHasOpPriority);
                log.info("  delta after transform: {}", transformedDelta.toString());
            }
        }

        log.info("finalDelta: {}", transformedDelta.toString());
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

        log.info("newRevision: {}", serverRevision + 1);
        log.info("masterDelta after compose: {}", updatedMaster.toString());

        redisService.updateNote(note, noteVersion);
        operationRelayer.relay(noteId, newTextOperation);
    }
}
