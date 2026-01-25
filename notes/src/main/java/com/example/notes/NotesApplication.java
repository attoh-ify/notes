package com.example.notes;

import com.example.notes.feat_document.collaborator_count_notifier.CollaboratorCountNotifier;
import com.example.notes.feat_document.formatter.impl.CharSequenceDocumentFormatter;
import com.example.notes.feat_relay_operation.operation_relayer.OperationRelayer;
import com.example.notes.shared.document_store.DocumentStore;
import com.example.notes.shared.document_store.impl.SimpleHashMapDocumentStore;
import com.example.notes.shared.operation_queue.OperationQueue;
import com.example.notes.shared.operation_queue.impl.OperationQueueImpl;
import com.example.notes.shared.operation_transformations.OperationTransformations;
import com.example.notes.shared.operation_transformations.impl.CharSequenceOperationTransformations;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class NotesApplication {

	public static void main(String[] args) {
		SpringApplication.run(NotesApplication.class, args);
	}

    @Bean
    public OperationQueue getOperationQueue() {
        return new OperationQueueImpl();
    }

    @Bean
    public OperationRelayer getOperationRelayer() {
        return new OperationRelayer();
    }

    @Bean
    public OperationTransformations getOperationTransformations() {
        return new CharSequenceOperationTransformations();
    }

    @Bean
    public DocumentStore getDocumentStore() {
        return new SimpleHashMapDocumentStore(CharSequenceDocumentFormatter::new);
    }

    @Bean
    public CollaboratorCountNotifier getCollaboratorCountNotifier() {
        return new CollaboratorCountNotifier();
    }
}
