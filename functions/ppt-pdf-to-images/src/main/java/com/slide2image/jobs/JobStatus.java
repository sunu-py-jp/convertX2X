package com.slide2image.jobs;

import com.slide2image.conversion.ConversionOptions;

public record JobStatus(
        String id,
        String status,
        String filename,
        ConversionOptions options,
        String createdAt,
        String updatedAt,
        Integer pageCount,
        String errorCode,
        String errorMessage) {
}
