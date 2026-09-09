package com.slide2image.jobs;

/** The result path is private storage metadata and is never returned in the status API. */
record JobRecord(JobStatus job, ResultLocation result, ConversionJobRequest request) {
    record ResultLocation(String storage, String container, String blobName, String contentType, String filename, int pageCount) {
    }
}
