package com.example.notes.dto.attribution;

public enum NewlineSuggestionType {
    /**
     * Newline has one or more review-run dependencies on the line behind it.
     */
    DEPENDENT,

    /**
     * Newline had no review-run dependencies at attribution build time.
     * FE should render it visibly/clickably because it is otherwise invisible.
     */
    STANDALONE
}