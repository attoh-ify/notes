package com.example.notes.dto.attribution;

import com.example.notes.dto.note.OpReference;
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
public class PendingFormatCancellation {
    private String groupId;
    @Builder.Default
    private List<OpReference> references = new ArrayList<>();
    private String cancellingOpId;
    private int retainComponentIndex;
    private int consumedBefore;
    private int length;
}
