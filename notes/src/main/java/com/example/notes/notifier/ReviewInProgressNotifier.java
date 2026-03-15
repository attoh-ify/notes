package com.example.notes.notifier;

import com.example.notes.dto.message_payload.ReviewInProgressResponsePayload;
import com.example.notes.entities.MessageType;
import com.example.notes.listeners.MessagePusher;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

public class ReviewInProgressNotifier {
    @Autowired
    public MessagePusher messagePusher;

    public void notifyReviewInProgress(UUID noteId, ReviewInProgressResponsePayload payload) {
        messagePusher.push(MessageType.REVIEW_IN_PROGRESS, noteId, payload);
    }
}
