package com.example.yapit.model;

import org.json.JSONObject;
import org.json.JSONException;

public final class GenerationRecord {
    public String generationId;
    public String profileId;
    public String text;
    public String providerId;
    public String modelId;
    public String modelName;
    public String modelVersion;
    public GenerationStatus status;
    public long createdAt;
    public long updatedAt;
    public long processingStartedAt;
    public long processingCompletedAt;
    public String progressMessage = "";
    public String audioFileName;
    public String mimeType;
    public String error;

    public JSONObject toJson() throws JSONException {
        return new JSONObject().put("schema_version", 1).put("generation_id", generationId)
                .put("profile_id", profileId).put("text", text).put("provider_id", providerId)
                .put("model_id", modelId).put("model_name", modelName).put("model_version", modelVersion)
                .put("status", status.name()).put("created_at", createdAt).put("updated_at", updatedAt)
                .put("processing_started_at", processingStartedAt).put("processing_completed_at", processingCompletedAt)
                .put("progress_message", progressMessage)
                .put("audio_file_name", audioFileName == null ? "" : audioFileName)
                .put("mime_type", mimeType == null ? "" : mimeType).put("error", error == null ? "" : error);
    }

    public static GenerationRecord fromJson(JSONObject json) throws JSONException {
        GenerationRecord r = new GenerationRecord();
        r.generationId = json.getString("generation_id"); r.profileId = json.optString("profile_id");
        r.text = json.optString("text"); r.providerId = json.getString("provider_id");
        r.modelId = json.getString("model_id"); r.modelName = json.optString("model_name", r.modelId);
        r.modelVersion = json.optString("model_version", "unknown");
        try { r.status = GenerationStatus.valueOf(json.optString("status", "FAILED")); } catch (Exception e) { r.status = GenerationStatus.FAILED; }
        r.createdAt = json.optLong("created_at"); r.updatedAt = json.optLong("updated_at");
        r.processingStartedAt = json.optLong("processing_started_at"); r.processingCompletedAt = json.optLong("processing_completed_at");
        r.progressMessage = json.optString("progress_message");
        r.audioFileName = json.optString("audio_file_name"); r.mimeType = json.optString("mime_type"); r.error = json.optString("error");
        return r;
    }
}
