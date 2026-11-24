package com.example.yapit.model;

import java.io.File;

public final class GenerationResult {
    public final File audioFile;
    public final String mimeType;
    public GenerationResult(File audioFile, String mimeType) { this.audioFile = audioFile; this.mimeType = mimeType; }
}
