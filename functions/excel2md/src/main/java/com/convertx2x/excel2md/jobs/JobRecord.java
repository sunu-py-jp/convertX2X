package com.convertx2x.excel2md.jobs;

import java.util.List;

/** Private metadata selecting exactly one complete upload attempt. */
record JobRecord(JobStatus job, ResultLocation result, ConversionJobRequest request) {
    record ResultLocation(String storage, String container, List<Artifact> artifacts,
                          int sheetCount, int warningCount) {
        ResultLocation { artifacts = List.copyOf(artifacts); }
    }
    record Artifact(String path, String blobName, String contentType, long sizeBytes, String eTag) { }
}
