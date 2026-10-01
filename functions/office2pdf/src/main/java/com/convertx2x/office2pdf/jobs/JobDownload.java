package com.convertx2x.office2pdf.jobs;

public record JobDownload(byte[] bytes, String contentType, String filename) { }
