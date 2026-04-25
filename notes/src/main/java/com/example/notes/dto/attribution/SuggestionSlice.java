package com.example.notes.dto.attribution;

import com.example.notes.dto.note.OpReference;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.*;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class SuggestionSlice {
    private int start;
    private int length;
    private OpReference ref;
}