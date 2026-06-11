package com.example.notes.dto.attribution;

public record AttributionBuildResult(
        AuditProjection projection,
        boolean revisionLogChanged
) {}