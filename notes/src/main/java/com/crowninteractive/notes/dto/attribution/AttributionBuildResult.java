package com.crowninteractive.notes.dto.attribution;

public record AttributionBuildResult(
        AuditProjection projection,
        boolean revisionLogChanged
) {}