package com.convertx2x.office2md.jobs;

import java.util.Optional;
import com.convertx2x.office2md.conversion.OutputFormat;

public interface JobService {
    JobStatus submit(byte[] input, String filename);
    default JobStatus submit(byte[] input, String filename, OutputFormat outputFormat) {
        if (outputFormat != OutputFormat.MARKDOWN) throw new UnsupportedOperationException("PDF output is unavailable.");
        return submit(input, filename);
    }
    Optional<JobStatus> find(String id);
    JobDownload download(String id, String artifact);
    default JobDownload downloadResult(String id) { return download(id, "document.md"); }
    JobDownload archive(String id);
    void process(String message);
    void poison(String message);
    default void maintenance() { }
}
