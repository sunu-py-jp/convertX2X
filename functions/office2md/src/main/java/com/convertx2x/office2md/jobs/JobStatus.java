package com.convertx2x.office2md.jobs;

/** Public job metadata. Storage locations and credentials are never serialized by the status API. */
public record JobStatus(String id, String status, String filename, String createdAt, String updatedAt,
                        Integer sectionCount, Integer warningCount, String errorCode, String errorMessage) { }
