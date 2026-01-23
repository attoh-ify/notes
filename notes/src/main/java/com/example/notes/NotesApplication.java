package com.example.notes;

import com.example.notes.shared.document_store.DocumentStore;
import com.example.notes.shared.document_store.impl.SimpleHashMapDocumentStore;
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
    public OperationTransformations getOperationTransformations() {
        return new CharSequenceOperationTransformations();
    }
}
