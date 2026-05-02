package com.example.notes.dto.attribution;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ReviewRun {
    private String text;
    @Builder.Default
    private Map<String, Object> baseAttributes = new HashMap<>();
    @Builder.Default
    private Map<String, Object> suggestionAttributes = new HashMap<>();
    private int logicalStart;
    private InsertSuggestion insertSuggestion;
    private DeleteSuggestion deleteSuggestion;
}
