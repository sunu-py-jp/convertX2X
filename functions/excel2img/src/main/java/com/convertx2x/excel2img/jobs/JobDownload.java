package com.convertx2x.excel2img.jobs;

public record JobDownload(byte[] bytes, String contentType, String filename) { }
