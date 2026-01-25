package com.example.notes.feat_document.models;

public record DocumentJoinResponse(
        int collaboratorCount,
        boolean hasError,
        String errorMessage,
        String text,
        int documentRevision
) {
    public static DocumentJoinResponse noError(int collaboratorCount, String text, int documentRevision) {
        return new DocumentJoinResponse(
                collaboratorCount,
                false,
                null,
                text,
                documentRevision
        );
    }

    public static DocumentJoinResponse withError(String errorMessage) {
        return new DocumentJoinResponse(
                -1,
                true,
                errorMessage,
                null,
                -1
        );
    }
}
