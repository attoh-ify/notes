package com.crowninteractive.notes.dto.attribution;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@AllArgsConstructor
public class AttributionBuildResult {
        private AuditProjection projection;
        private boolean revisionLogChanged;
}