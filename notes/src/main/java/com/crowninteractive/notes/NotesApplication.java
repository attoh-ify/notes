package com.crowninteractive.notes;

import com.crowninteractive.notes.notifier.*;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class NotesApplication {
    public static void main(String[] args) {
        SpringApplication.run(NotesApplication.class, args);
    }

    @Bean
    public OperationRelayer getOperationRelayer() {
        return new OperationRelayer();
    }

    @Bean
    public CollaboratorCountNotifier getCollaboratorCountNotifier() {
        return new CollaboratorCountNotifier();
    }

    @Bean
    public CursorNotifier getCursorNotifier() {
        return new CursorNotifier();
    }

    @Bean
    public ReviewInProgressNotifier getReviewInProgressNotifier() {
        return new ReviewInProgressNotifier();
    }

    @Bean
    public CollaborationModeNotifier getCollaborationModeNotifier() {
        return new CollaborationModeNotifier();
    }

    @Bean
    public SoloSyncNotifier getSoloSyncNotifier() {
        return new SoloSyncNotifier();
    }
}
