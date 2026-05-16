package com.example.notes.dto.attribution;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.*;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class DeleteSuggestion {
    private String groupId;
    private String actorEmail;
    private String createdAt;
    @Builder.Default
    private DeleteSuggestionType type = DeleteSuggestionType.TEXT;

    public enum DeleteSuggestionType {
        TEXT,
        SINGLE_LINE,
        MULTI_LINE
    }
}