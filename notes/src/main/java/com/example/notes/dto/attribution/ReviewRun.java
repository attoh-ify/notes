package com.example.notes.dto.attribution;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
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
    @Builder.Default
    private List<Reference> references = new ArrayList<>();
    private int logicalStart;
    private InsertSuggestion insertSuggestion;
    private DeleteSuggestion deleteSuggestion;
}