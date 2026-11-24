package com.example.yapit.provider;

import com.example.yapit.model.ModelMetadata;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

final class ConfiguredModelParser {
    static List<ModelMetadata> parse(String providerId, JSONObject config) {
        List<ModelMetadata> result = new ArrayList<>();
        JSONArray models = config.optJSONArray("models");
        if (models != null) for (int i = 0; i < models.length(); i++) {
            JSONObject item = models.optJSONObject(i);
            if (item != null && !item.optString("id").isBlank()) result.add(ModelMetadata.fromRegistration(providerId, item));
        }
        return result;
    }
}
