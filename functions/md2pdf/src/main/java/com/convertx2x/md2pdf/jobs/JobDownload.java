package com.convertx2x.md2pdf.jobs;

public record JobDownload(byte[] bytes, String contentType, String filename) { }
