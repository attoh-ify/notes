package com.example.notes.controllers;

import com.example.notes.config.activeMq.MessageProducer;
import com.example.notes.dto.enqueue.OperationQueueInPayload;
import com.example.notes.dto.message_payload.CursorPayload;
import com.example.notes.dto.message_payload.SoloSyncAckPayload;
import com.example.notes.dto.ot.TextOperation;
import com.example.notes.notifier.CursorNotifier;
import com.example.notes.notifier.SoloSyncNotifier;
import com.example.notes.services.NoteService;
import com.example.notes.services.RedisService;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Controller;

import java.security.Principal;
import java.util.Map;
import java.util.UUID;

@Controller
public class CollaborationSocketController {
    private final MessageProducer messageProducer;
    private final CursorNotifier cursorNotifier;
    private final RedisService redisService;
    private final NoteService noteService;
    private final SoloSyncNotifier soloSyncNotifier;

    public CollaborationSocketController(
            MessageProducer messageProducer,
            CursorNotifier cursorNotifier, RedisService redisService, NoteService noteService, SoloSyncNotifier soloSyncNotifier
    ) {
        this.messageProducer = messageProducer;
        this.cursorNotifier = cursorNotifier;
        this.redisService = redisService;
        this.noteService = noteService;
        this.soloSyncNotifier = soloSyncNotifier;
    }

    @MessageMapping("/note/{noteId}/operation")
    public void enqueueOperation(
            @DestinationVariable UUID noteId,
            TextOperation operation,
            Principal principal,
            StompHeaderAccessor accessor
    ) {
        requireJoinedNote(noteId, principal, accessor);

        OperationQueueInPayload payload = new OperationQueueInPayload(
                noteId,
                operation.getOpId(),
                operation.getRevision(),
                principal.getName(),
                operation.getDelta()
        );

        messageProducer.sendMessage(payload, noteId);
    }

    @MessageMapping("/note/{noteId}/cursor")
    public void sendCursor(
            @DestinationVariable UUID noteId,
            CursorPayload payload,
            Principal principal,
            StompHeaderAccessor accessor
    ) {
        requireJoinedNote(noteId, principal, accessor);

        cursorNotifier.notifyCursorChange(
                noteId,
                new CursorPayload(principal.getName(), payload.getPosition(), payload.getLength())
        );
    }

    @MessageMapping("/note/{noteId}/heartbeat")
    public void heartbeat(
            @DestinationVariable UUID noteId,
            Principal principal,
            StompHeaderAccessor accessor
    ) {
        requireJoinedNote(noteId, principal, accessor);
    }

    @MessageMapping("/note/{noteId}/solo-sync")
    public void soloSync(
            @DestinationVariable UUID noteId,
            TextOperation operation,
            Principal principal,
            StompHeaderAccessor accessor
    ) {
        requireJoinedNote(noteId, principal, accessor);

        String opId = operation != null ? operation.getOpId() : null;

        try {
            if (redisService.isCollaborativeMode(noteId) && operation != null) {
                OperationQueueInPayload payload = new OperationQueueInPayload(
                        noteId,
                        operation.getOpId(),
                        operation.getRevision(),
                        principal.getName(),
                        operation.getDelta()
                );

                messageProducer.sendMessage(payload, noteId);

                /*
                 * Do NOT send success SOLO_SYNC_ACK here.
                 * The queue has accepted the op, but it has not processed it yet.
                 * The real ack is the OPERATION relay.
                 */
                return;
            }

            int newRevision = noteService.soloSyncFromJoinedSession(
                    principal.getName(),
                    noteId,
                    operation
            );

            if (newRevision == 0) {
                return;
            }

            soloSyncNotifier.notifySoloSyncAck(
                    noteId,
                    new SoloSyncAckPayload(
                            noteId,
                            opId,
                            true,
                            newRevision,
                            null
                    )
            );
        } catch (Exception e) {
            soloSyncNotifier.notifySoloSyncAck(
                    noteId,
                    new SoloSyncAckPayload(
                            noteId,
                            opId,
                            false,
                            null,
                            e.getMessage()
                    )
            );
        }
    }

    private void requireJoinedNote(
            UUID noteId,
            Principal principal,
            StompHeaderAccessor accessor
    ) {
        if (principal == null) {
            throw new IllegalStateException("Unauthenticated websocket message");
        }

        Map<String, Object> sessionAttributes = accessor.getSessionAttributes();

        if (sessionAttributes == null) {
            throw new IllegalStateException("Missing websocket session attributes");
        }

        Object joinedNoteId = sessionAttributes.get("noteId");

        if (joinedNoteId == null || !noteId.toString().equals(String.valueOf(joinedNoteId))) {
            throw new IllegalStateException("Websocket session is not joined to this note");
        }

        String sessionId = accessor.getSessionId();

        if (sessionId != null) {
            redisService.refreshCollaboratorSessionHeartbeat(
                    noteId,
                    sessionId,
                    principal.getName()
            );
        }
    }
}