package com.example.notes.feat_document.collaborator_count_notifier;

import com.example.notes.shared.message_pusher.MessagePusher;
import com.example.notes.shared.message_pusher.MessageType;
import com.example.notes.shared.model.message_out_payload.CollaborationCountPayload;
import org.springframework.beans.factory.annotation.Autowired;

public class CollaboratorCountNotifier {
    @Autowired
    public MessagePusher messagePusher;

    public void notifyCount(String docId, CollaborationCountPayload collaborationCount) {
        messagePusher.push(MessageType.COLLABORATOR_COUNT, docId, collaborationCount);
    }
}
