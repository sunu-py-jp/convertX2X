package com.slide2image.jobs;

import com.slide2image.conversion.ConversionResult;
import java.util.Optional;

/** Small storage boundary so retries and state transitions can be tested without Azure. */
interface JobStore {
    void create(JobRecord job, byte[] input);
    default void submit(JobRecord job, byte[] input, String message) { create(job, input); enqueue(message); }
    void ensure(JobRecord job);
    void enqueue(String message);
    Optional<JobRecord> find(String id);
    void validateLocations(ConversionJobRequest request);
    byte[] readInput(ConversionJobRequest.BlobSource source);
    JobRecord.ResultLocation writeResult(ConversionJobRequest request, ConversionResult result);
    ImageOutput beginImages(ConversionJobRequest request);
    ConversionResult readResult(JobRecord.ResultLocation result);
    JobLock lock(String id);

    record InputData(byte[] bytes, JobRecord.SourceInfo info) {}
    default InputData readInputData(ConversionJobRequest.BlobSource source) { return new InputData(readInput(source), null); }
    default JobRecord.ResultLocation writeResult(ConversionJobRequest request, ConversionResult result, JobRecord.SourceInfo input) { return writeResult(request, result); }
    default ImageOutput beginImages(ConversionJobRequest request, JobRecord.SourceInfo input) { return beginImages(request); }
    default void sendNotification(JobRecord record) {}
    default void maintain() {}

    interface ImageOutput {
        void writePage(int pageNumber, ConversionResult image);
        JobRecord.ResultLocation finish();
    }

    interface JobLock extends AutoCloseable {
        void update(JobRecord job);
        @Override void close();
    }
}
