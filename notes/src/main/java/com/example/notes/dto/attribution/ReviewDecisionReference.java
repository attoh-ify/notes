package com.example.notes.dto.attribution;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReviewDecisionReference {
    private String opId;
    private int componentIndex;
    private int componentStart;
    private int length;
    // Only needed for format retain components.
    // Null means insert/delete/plain component slice.
    private String attributeKey;
}