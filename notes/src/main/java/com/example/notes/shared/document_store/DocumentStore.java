package com.example.notes.shared.document_store;

import com.example.notes.feat_document.formatter.DocumentFormatter;
import com.example.notes.shared.model.DocumentModel;
import com.example.notes.shared.operation_transformations.OperationTransformations;
import lombok.Getter;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.function.Supplier;

public abstract class DocumentStore {
    final public Supplier<DocumentFormatter> documentFormatterFactory;

    @Getter
    @Autowired
    private OperationTransformations operationTransformations;

    public DocumentStore(Supplier<DocumentFormatter> documentFormatterFactory) {
        this.documentFormatterFactory = documentFormatterFactory;
    }

    public abstract DocumentModel addEmptyDocument(String userId, String docId);

    public abstract DocumentModel getDocumentFromDocId(String docId);

    public abstract DocumentModel getDocumentFromUserId(String userId);

    public abstract DocumentModel removeDocument(String docId);

    public abstract void addCollaboratorToDocument(String userId, String docId);

    public abstract DocumentModel removeCollaboratorFromDocument(String userId);

    public abstract boolean hasDocument(String docId);
}
