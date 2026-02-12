package com.example.notes.dto.note;

import java.util.List;

public record DocumentJoinResponse(
        List<String> collaborators,
        boolean hasError,
        String errorMessage,
        String text,
        int documentRevision
) {
    public static DocumentJoinResponse noError(List<String> collaborators, String text, int documentRevision) {
        return new DocumentJoinResponse(
                collaborators,
                false,
                null,
                text,
                documentRevision
        );
    }

    public static DocumentJoinResponse withError(String errorMessage) {
        return new DocumentJoinResponse(
                null,
                true,
                errorMessage,
                null,
                -1
        );
    }
}
