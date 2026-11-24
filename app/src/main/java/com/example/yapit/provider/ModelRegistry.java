package com.example.yapit.provider;

import android.content.Context;
import com.example.yapit.BuildConfig;
import com.example.yapit.model.ModelMetadata;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Loads provider classes and model registrations from assets without coupling the UI or workers to an engine. */
public final class ModelRegistry {
    private final Map<String, ProviderRegistration> providers = new LinkedHashMap<>();
    private final Map<String, ModelMetadata> models = new LinkedHashMap<>();

    public static ModelRegistry load(Context context) throws Exception {
        ModelRegistry registry = new ModelRegistry();
        JSONObject root = new JSONObject(readAsset(context, "model_providers.json"));
        JSONArray items = root.getJSONArray("providers");
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.getJSONObject(i);
            if (!item.optBoolean("enabled", true)) continue;
            String id = item.getString("id");
            String className = item.getString("class");
            JSONObject config = resolvePlaceholders(item.optJSONObject("config") == null ? new JSONObject() : item.getJSONObject("config"));
            Object instance = Class.forName(className).getDeclaredConstructor().newInstance();
            if (!(instance instanceof ModelProvider)) throw new IllegalArgumentException(className + " is not a ModelProvider");
            ModelProvider provider = (ModelProvider) instance;
            if (!id.equals(provider.providerId())) throw new IllegalArgumentException("Provider ID mismatch: " + id);
            if (!provider.isAvailable(context, config)) continue;
            ProviderRegistration registration = new ProviderRegistration(id, provider, config);
            registry.providers.put(id, registration);
            for (ModelMetadata model : provider.discoverModels(context, config)) registry.models.put(model.stableKey(), model);
        }
        return registry;
    }

    public List<ModelMetadata> models() { return new ArrayList<>(models.values()); }
    public ModelMetadata requireModel(String providerId, String modelId) {
        ModelMetadata model = models.get(providerId + ":" + modelId);
        if (model == null) throw new IllegalStateException("Model is no longer registered: " + providerId + ":" + modelId);
        return model;
    }
    public ProviderRegistration requireProvider(String providerId) {
        ProviderRegistration p = providers.get(providerId);
        if (p == null) throw new IllegalStateException("Provider is not available: " + providerId);
        return p;
    }

    private static String readAsset(Context context, String path) throws Exception {
        try (InputStream in = context.getAssets().open(path); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int n; while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static JSONObject resolvePlaceholders(JSONObject input) throws Exception {
        String text = input.toString().replace("${VOICE_API_BASE_URL}", escapeForJson(BuildConfig.VOICE_API_BASE_URL))
                .replace("${VOICE_API_KEY}", escapeForJson(BuildConfig.VOICE_API_KEY));
        return new JSONObject(text);
    }

    private static String escapeForJson(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
