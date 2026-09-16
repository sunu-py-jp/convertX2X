package com.slide2image.jobs;

import java.util.Map;

/** Terminal state is the durable notification outbox; retries never repeat conversion. */
record JobRecord(JobStatus job, ResultLocation result, ConversionJobRequest request, SourceInfo input,
                 String notificationSentAt, boolean resultExpired, boolean submissionPending, int artifactTrackingVersion) {
    JobRecord(JobStatus job, ResultLocation result, ConversionJobRequest request, SourceInfo input,
              String notificationSentAt, boolean resultExpired, boolean submissionPending) {
        this(job, result, request, input, notificationSentAt, resultExpired, submissionPending, 1);
    }
    JobRecord(JobStatus job, ResultLocation result, ConversionJobRequest request, SourceInfo input,
              String notificationSentAt, boolean resultExpired) { this(job, result, request, input, notificationSentAt, resultExpired, false); }
    JobRecord(JobStatus job, ResultLocation result, ConversionJobRequest request) { this(job, result, request, null, null, false); }
    record SourceInfo(String storage, String container, String blobName, String eTag) {}
    record ResultLocation(String storage, String container, String blobName, String contentType, String filename,
                          int pageCount, long sizeBytes, String sha256, String eTag) {
        ResultLocation(String storage, String container, String blobName, String contentType, String filename, int pageCount) {
            this(storage, container, blobName, contentType, filename, pageCount, 0, null, null);
        }
    }
    boolean terminal() { return "succeeded".equals(job.status()) || "failed".equals(job.status()); }
    boolean notificationPending() { return terminal() && request.notification() != null && notificationSentAt == null; }
    Map<String, Object> notificationEvent() {
        var event = new java.util.LinkedHashMap<String, Object>();
        event.put("version", 1); event.put("eventId", job.id() + ":" + job.status());
        event.put("jobId", job.id()); event.put("status", job.status());
        var source = request.input();
        event.put("input", input != null ? input : new SourceInfo(source.storage(), source.container(), source.blobName(), null));
        event.put("metadata", request.metadata()); event.put("result", result);
        event.put("error", job.errorCode() == null ? null : Map.of("code", job.errorCode(), "retryable", false));
        event.put("completedAt", job.updatedAt());
        return event;
    }
}
