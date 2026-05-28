package com.example.notes.controllers;

import com.example.notes.config.activeMq.MessageProducer;
import com.example.notes.dto.enqueue.OperationQueueInPayload;
import com.example.notes.dto.message_payload.CursorPayload;
import com.example.notes.dto.ot.TextOperation;
import com.example.notes.notifier.CursorNotifier;
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

    public CollaborationSocketController(
            MessageProducer messageProducer,
            CursorNotifier cursorNotifier
    ) {
        this.messageProducer = messageProducer;
        this.cursorNotifier = cursorNotifier;
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
                new CursorPayload(principal.getName(), payload.getPosition())
        );
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
    }
}