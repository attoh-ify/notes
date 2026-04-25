package com.example.notes.dto.attribution;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class InsertSuggestion {
    private String groupId;
    private String actorEmail;
    private String createdAt;
    @Builder.Default
    private List<SuggestionSlice> references = new ArrayList<>();
    private int startIndex;
}
