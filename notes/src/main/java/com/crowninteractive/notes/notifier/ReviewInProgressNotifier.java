package com.crowninteractive.notes.notifier;

import com.crowninteractive.notes.dto.message_payload.ReviewInProgressResponsePayload;
import com.crowninteractive.notes.dto.message_payload.MessageType;
import com.crowninteractive.notes.listeners.MessagePusher;
import org.springframework.beans.factory.annotation.Autowired;

public class ReviewInProgressNotifier {
    @Autowired
    public MessagePusher messagePusher;

    public void notifyReviewInProgress(String noteId, ReviewInProgressResponsePayload payload) {
        messagePusher.push(MessageType.REVIEW_IN_PROGRESS, noteId, payload);
    }
}
