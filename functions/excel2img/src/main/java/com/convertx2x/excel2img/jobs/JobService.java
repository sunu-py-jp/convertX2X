package com.convertx2x.excel2img.jobs;

import java.util.Optional;
import com.convertx2x.excel2img.conversion.RenderOptions;

public interface JobService {
    default JobStatus submit(byte[] input, String filename) { return submit(input, filename, RenderOptions.defaults()); }
    JobStatus submit(byte[] input, String filename, RenderOptions options);
    Optional<JobStatus> find(String id);
    JobDownload download(String id, String artifact);
    JobDownload archive(String id);
    void process(String message);
    void poison(String message);
    default void maintenance() { }
}
