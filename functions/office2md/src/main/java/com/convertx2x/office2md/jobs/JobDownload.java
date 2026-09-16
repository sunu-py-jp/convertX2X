package com.convertx2x.office2md.jobs;

public record JobDownload(byte[] bytes, String contentType, String filename) { }
