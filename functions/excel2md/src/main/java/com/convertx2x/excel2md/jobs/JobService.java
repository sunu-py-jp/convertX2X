package com.convertx2x.excel2md.jobs;

import java.util.Optional;

public interface JobService {
    JobStatus submit(byte[] input, String filename);
    Optional<JobStatus> find(String id);
    JobDownload download(String id, String artifact);
    JobDownload archive(String id);
    void process(String message);
    void poison(String message);
}
