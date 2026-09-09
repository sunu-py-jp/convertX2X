package com.convertx2x.excel2md.jobs;

public record JobDownload(byte[] bytes, String contentType, String filename) { }
