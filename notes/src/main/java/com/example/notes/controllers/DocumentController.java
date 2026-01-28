package com.example.notes.controllers;

import com.aventrix.jnanoid.jnanoid.NanoIdUtils;
import com.example.notes.notifier.CollaboratorCountNotifier;
import com.example.notes.dto.note.DocumentCreateResponse;
import com.example.notes.dto.note.DocumentJoinResponse;
import com.example.notes.dto.user.UserModel;
import com.example.notes.shared.document_store.DocumentStore;
import com.example.notes.dto.note.DocumentModel;
import com.example.notes.dto.message_payload.CollaborationCountPayload;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/doc")
public class DocumentController {
    @Autowired
    private DocumentStore documentStore;

    @Autowired
    private CollaboratorCountNotifier collaboratorCountNotifier;

    @PostMapping("/create")
    public DocumentCreateResponse createDoc(@RequestBody UserModel user) {
        String docId = NanoIdUtils.randomNanoId();
        System.out.println("Creating document " + docId);
        documentStore.addEmptyDocument(user.getUserId(), docId);
        return new DocumentCreateResponse(docId);
    }

    @PostMapping("/{docId}")
    public DocumentJoinResponse joinDoc(@PathVariable String docId, @RequestBody UserModel user) {
        DocumentModel doc = documentStore.getDocumentFromDocId(docId);
        if (doc == null) {
            return DocumentJoinResponse.withError("Document with id = " + docId + " does not exist");
        } else {
            documentStore.addCollaboratorToDocument(user.getUserId(), docId);
            collaboratorCountNotifier.notifyCount(docId, new CollaborationCountPayload(doc.getCollaboratorCount()));

            return DocumentJoinResponse.noError(
                    doc.getCollaboratorCount(),
                    doc.getDocText(),
                    doc.getRevision()
            );
        }
    }
}
