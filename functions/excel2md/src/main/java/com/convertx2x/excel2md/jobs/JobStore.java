package com.convertx2x.excel2md.jobs;

import com.convertx2x.excel2md.conversion.ConversionResult;
import java.util.Optional;

interface JobStore {
    void create(JobRecord job, byte[] input);
    void ensure(JobRecord job);
    void enqueue(String message);
    Optional<JobRecord> find(String id);
    void validateLocations(ConversionJobRequest request);
    byte[] readInput(ConversionJobRequest.BlobSource source);
    JobRecord.ResultLocation writeResult(ConversionJobRequest request, ConversionResult result);
    JobDownload readResult(JobRecord.ResultLocation result, String artifact);
    JobDownload readArchive(JobRecord.ResultLocation result);
    JobLock lock(String id);

    interface JobLock extends AutoCloseable {
        void update(JobRecord job);
        @Override void close();
    }
}
