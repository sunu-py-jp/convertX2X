package com.convertx2x.excel2img.jobs;

import java.util.Map;

/** Public job metadata. Storage locations and credentials are never serialized by the status API. */
public record JobStatus(String id, String status, String filename, String createdAt, String updatedAt,
                        Integer sectionCount, Integer warningCount, String errorCode, String errorMessage,
                        Map<String, Object> errorDetails) {
    public JobStatus(String id, String status, String filename, String createdAt, String updatedAt,
                     Integer sectionCount, Integer warningCount, String errorCode, String errorMessage) {
        this(id, status, filename, createdAt, updatedAt, sectionCount, warningCount, errorCode, errorMessage, Map.of());
    }
    public JobStatus { errorDetails = errorDetails == null ? Map.of() : Map.copyOf(errorDetails); }
}
