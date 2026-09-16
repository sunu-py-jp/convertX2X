package com.convertx2x.office2md.jobs;

import java.util.List;

/** Private state is the durable notification outbox and selects one complete upload attempt. */
record JobRecord(JobStatus job, ResultLocation result, ConversionJobRequest request, String inputETag,
                 String notificationSentAt, String artifactsExpiredAt, boolean submissionPending, int artifactTrackingVersion) {
    JobRecord(JobStatus job, ResultLocation result, ConversionJobRequest request, String inputETag, String notificationSentAt, String artifactsExpiredAt, boolean submissionPending) {
        this(job, result, request, inputETag, notificationSentAt, artifactsExpiredAt, submissionPending, 1);
    }
    JobRecord(JobStatus job, ResultLocation result, ConversionJobRequest request, String inputETag, String notificationSentAt, String artifactsExpiredAt) {
        this(job, result, request, inputETag, notificationSentAt, artifactsExpiredAt, false);
    }
    JobRecord(JobStatus job, ResultLocation result, ConversionJobRequest request) { this(job, result, request, null, null, null); }
    JobRecord withNotificationSent(String at) { return new JobRecord(job, result, request, inputETag, at, artifactsExpiredAt, submissionPending, artifactTrackingVersion); }
    JobRecord withExpiredArtifacts(String at) { return new JobRecord(job, result, request, inputETag, notificationSentAt, at, submissionPending, artifactTrackingVersion); }
    JobRecord withTrackingVersion(int version) { return new JobRecord(job, result, request, inputETag, notificationSentAt, artifactsExpiredAt, submissionPending, version); }
    boolean pendingNotification() { return request != null && request.notification() != null && notificationSentAt == null
            && ("succeeded".equals(job.status()) || "failed".equals(job.status())); }
    record ResultLocation(String storage, String container, List<Artifact> artifacts,
                          int sectionCount, int warningCount, Artifact manifest) {
        ResultLocation(String storage, String container, List<Artifact> artifacts, int sectionCount, int warningCount) {
            this(storage, container, artifacts, sectionCount, warningCount, null);
        }
        ResultLocation { artifacts = List.copyOf(artifacts); }
    }
    record Artifact(String path, String blobName, String contentType, long sizeBytes, String eTag, String sha256) {
        Artifact(String path, String blobName, String contentType, long sizeBytes, String eTag) { this(path, blobName, contentType, sizeBytes, eTag, null); }
    }
}
