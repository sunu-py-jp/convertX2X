package com.convertx2x.office2md.jobs;

import com.convertx2x.office2md.conversion.ConversionResult;
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
    record InputData(byte[] bytes, String eTag) { }
    default InputData readInputVersioned(ConversionJobRequest.BlobSource source) { return new InputData(readInput(source), null); }
    default JobRecord.ResultLocation writeResult(ConversionJobRequest request, ConversionResult result, String inputETag) { return writeResult(request, result); }
    default void notifyResult(JobRecord record) { }
    default void maintenance(java.util.function.Consumer<String> action) { }
    default void cleanup(JobRecord record, JobLock lock, java.time.Instant now) { }


    interface JobLock extends AutoCloseable {
        void update(JobRecord job);
        default void delete() { throw new UnsupportedOperationException(); }
        @Override void close();
    }
}
