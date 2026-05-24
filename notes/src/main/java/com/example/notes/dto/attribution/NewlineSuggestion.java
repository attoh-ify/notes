package com.example.notes.dto.attribution;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.*;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class NewlineSuggestion {
    private String groupId;
    private String actorEmail;
    private String createdAt;

    @Builder.Default
    private List<Reference> references = new ArrayList<>();

    @Builder.Default
    private List<String> dependsOnReviewRunIds = new ArrayList<>();

    @Builder.Default
    private NewlineSuggestionType type = NewlineSuggestionType.STANDALONE;
}