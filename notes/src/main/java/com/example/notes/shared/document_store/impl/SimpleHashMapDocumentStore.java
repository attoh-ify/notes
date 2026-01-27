package com.example.notes.shared.document_store.impl;

import com.example.notes.feat_document.formatter.DocumentFormatter;
import com.example.notes.shared.document_store.DocumentStore;
import com.example.notes.shared.model.DocumentModel;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

public class SimpleHashMapDocumentStore extends DocumentStore {
    // DocId -> DocState
    private final ConcurrentHashMap<String, DocumentModel> store = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> userIdToDocIdMap = new ConcurrentHashMap<>();

    public SimpleHashMapDocumentStore(Supplier<DocumentFormatter> documentFormatterFactory) {
        super(documentFormatterFactory);
    }

    @Override
    public String toString() {
        return "SimpleHashMapDocumentStore{" +
                "store=" + store +
                ", userIdToDocIdMap=" + userIdToDocIdMap +
                '}';
    }

    @Override
    public void addEmptyDocument(String userId, String docId) {
        DocumentModel newDocState =
                new DocumentModel(
                        docId, documentFormatterFactory.get(),
                        getOperationTransformations());
        System.out.println("Adding empty document " + newDocState);
        store.put(docId, newDocState);
        addCollaboratorToDocument(userId, docId);
    }

    @Override
    public DocumentModel getDocumentFromDocId(String docId) {
        return store.get(docId);
    }

    @Override
    public DocumentModel getDocumentFromUserId(String userId) {
        return store.get(userIdToDocIdMap.get(userId));
    }

    @Override
    public void removeDocument(String docId) {
        store.remove(docId);
    }

    @Override
    public void addCollaboratorToDocument(String userId, String docId) {
        System.out.println("UserId: " + userId);
        System.out.println("DocId: " + docId);
        userIdToDocIdMap.put(userId, docId);
        getDocumentFromDocId(docId).incrementCollaboratorCount();
    }

    @Override
    public DocumentModel removeCollaboratorFromDocument(String userId) {
        var doc = getDocumentFromUserId(userId);
        int newCount = doc.decrementCollaboratorCount();
//        userIdToDocIdMap.remove(userId);
        if (newCount == 0) {
            removeDocument(doc.getId());
        }
        return doc;
    }

    @Override
    public boolean hasDocument(String docId) {
        return store.containsKey(docId);
    }
}
