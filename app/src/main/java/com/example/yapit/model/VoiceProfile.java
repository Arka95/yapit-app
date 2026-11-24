package com.example.yapit.model;

import org.json.JSONObject;
import org.json.JSONException;

public final class VoiceProfile {
    public String profileId;
    public String displayName;
    public String sampleFileName;
    public long createdAt;
    public JSONObject enrollments = new JSONObject();

    @Override public String toString() { return displayName; }

    public String enrollmentIdFor(String stableModelKey) {
        return enrollments.optJSONObject(stableModelKey) == null ? "" : enrollments.optJSONObject(stableModelKey).optString("enrollment_id");
    }

    public JSONObject toJson() throws JSONException {
        return new JSONObject().put("schema_version", 1).put("profile_id", profileId)
                .put("display_name", displayName).put("sample_file_name", sampleFileName == null ? "" : sampleFileName)
                .put("created_at", createdAt).put("enrollments", enrollments);
    }

    public static VoiceProfile fromJson(JSONObject json) throws JSONException {
        VoiceProfile p = new VoiceProfile(); p.profileId = json.getString("profile_id");
        p.displayName = json.optString("display_name", "My voice"); p.sampleFileName = json.optString("sample_file_name");
        p.createdAt = json.optLong("created_at"); p.enrollments = json.optJSONObject("enrollments");
        if (p.enrollments == null) p.enrollments = new JSONObject(); return p;
    }
}
