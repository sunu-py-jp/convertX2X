package com.slide2image.jobs;

import com.slide2image.conversion.ConversionOptions;
import com.slide2image.conversion.ConversionResult;
import java.util.Optional;

public interface JobService {
    JobStatus submit(byte[] input, String filename, ConversionOptions options);
    Optional<JobStatus> find(String id);
    ConversionResult download(String id);
    void process(String message);
    void poison(String message);
}
