package com.example.notes.shared.document_store.impl;

import com.example.notes.shared.formatter.DocumentFormatter;
import com.example.notes.shared.document_store.NoteStore;
import com.example.notes.dto.note.DocumentModel;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

public class SimpleHashMapDocumentStore extends NoteStore {
    // noteId -> DocState
    private final ConcurrentHashMap<UUID, DocumentModel> store = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, UUID> userIdTonoteIdMap = new ConcurrentHashMap<>();

    public SimpleHashMapDocumentStore(Supplier<DocumentFormatter> documentFormatterFactory) {
        super(documentFormatterFactory);
    }

    @Override
    public String toString() {
        return "SimpleHashMapDocumentStore{" +
                "store=" + store +
                ", userIdTonoteIdMap=" + userIdTonoteIdMap +
                '}';
    }

    @Override
    public void addEmptyNote(UUID userId, UUID noteId) {
        DocumentModel newDocState =
                new DocumentModel(
                        noteId, documentFormatterFactory.get(),
                        getOperationTransformations());
        store.put(noteId, newDocState);
        addCollaboratorToNote(userId, noteId);
    }

    @Override
    public DocumentModel getNoteFromNoteId(UUID noteId) {
        return store.get(noteId);
    }

    @Override
    public DocumentModel getNoteFromUserId(UUID userId) {
        return store.get(userIdTonoteIdMap.get(userId));
    }

    @Override
    public void removeNote(UUID noteId) {
        store.remove(noteId);
    }

    @Override
    public void addCollaboratorToNote(UUID userId, UUID noteId) {
        userIdTonoteIdMap.put(userId, noteId);
        getNoteFromNoteId(noteId).incrementCollaboratorCount();
    }

    @Override
    public DocumentModel removeCollaboratorFromNote(UUID userId) {
        var doc = getNoteFromUserId(userId);
        int newCount = doc.decrementCollaboratorCount();
//        userIdTonoteIdMap.remove(userId);
        if (newCount == 0) {
            removeNote(doc.getId());
        }
        return doc;
    }

    @Override
    public boolean hasDocument(UUID noteId) {
        return store.containsKey(noteId);
    }
}
