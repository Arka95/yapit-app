package com.example.yapit.provider;

import com.example.yapit.model.GenerationStatus;

public interface ProgressSink {
    void onStage(GenerationStatus status, String message) throws Exception;
}
