package com.example.notes.shared.document_store;

import com.example.notes.shared.formatter.DocumentFormatter;
import com.example.notes.dto.note.DocumentModel;
import com.example.notes.shared.operation_transformations.OperationTransformations;
import lombok.Getter;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;
import java.util.function.Supplier;

public abstract class NoteStore {
    final public Supplier<DocumentFormatter> documentFormatterFactory;

    @Getter
    @Autowired
    private OperationTransformations operationTransformations;

    public NoteStore(Supplier<DocumentFormatter> documentFormatterFactory) {
        this.documentFormatterFactory = documentFormatterFactory;
    }

    public abstract void addEmptyNote(UUID userId, UUID noteId);

    public abstract DocumentModel getNoteFromNoteId(UUID noteId);

    public abstract DocumentModel getNoteFromUserId(UUID userId);

    public abstract void removeNote(UUID docId);

    public abstract void addCollaboratorToNote(UUID userId, UUID noteId);

    public abstract DocumentModel removeCollaboratorFromNote(UUID userId);

    public abstract boolean hasDocument(UUID noteId);
}
