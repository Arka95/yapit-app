package com.example.yapit.model;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONException;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

public final class ModelMetadata {
    public final String providerId;
    public final String modelId;
    public final String displayName;
    public final String version;
    public final String description;
    private final Set<ModelCapability> capabilities;

    public ModelMetadata(String providerId, String modelId, String displayName, String version,
                         String description, Set<ModelCapability> capabilities) {
        this.providerId = providerId;
        this.modelId = modelId;
        this.displayName = displayName;
        this.version = version;
        this.description = description;
        EnumSet<ModelCapability> copy = capabilities.isEmpty() ? EnumSet.noneOf(ModelCapability.class) : EnumSet.copyOf(capabilities);
        this.capabilities = Collections.unmodifiableSet(copy);
    }

    public boolean supports(ModelCapability capability) { return capabilities.contains(capability); }
    public Set<ModelCapability> capabilities() { return capabilities; }
    public String stableKey() { return providerId + ":" + modelId; }

    public String capabilitySummary() {
        if (capabilities.contains(ModelCapability.VOICE_CLONING)) return "Voice cloning · " + (supports(ModelCapability.OFFLINE) ? "On-device" : "Network");
        return "Text to speech · " + (supports(ModelCapability.OFFLINE) ? "On-device" : "Network");
    }

    public JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        JSONArray caps = new JSONArray();
        for (ModelCapability capability : capabilities) caps.put(capability.name());
        return json.put("provider_id", providerId).put("model_id", modelId)
                .put("display_name", displayName).put("version", version)
                .put("description", description).put("capabilities", caps);
    }

    public static ModelMetadata fromRegistration(String providerId, JSONObject json) {
        EnumSet<ModelCapability> caps = EnumSet.noneOf(ModelCapability.class);
        JSONArray values = json.optJSONArray("capabilities");
        if (values != null) for (int i = 0; i < values.length(); i++) {
            try { caps.add(ModelCapability.valueOf(values.optString(i).toUpperCase(Locale.US))); }
            catch (IllegalArgumentException ignored) { }
        }
        return new ModelMetadata(providerId, json.optString("id"), json.optString("display_name", json.optString("id")),
                json.optString("version", "unknown"), json.optString("description", ""), caps);
    }

    @Override public String toString() { return displayName; }
}
