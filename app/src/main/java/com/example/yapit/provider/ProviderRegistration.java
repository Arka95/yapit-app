package com.example.yapit.provider;

import org.json.JSONObject;

public final class ProviderRegistration {
    public final String id;
    public final ModelProvider provider;
    public final JSONObject config;
    public ProviderRegistration(String id, ModelProvider provider, JSONObject config) {
        this.id = id; this.provider = provider; this.config = config;
    }
}
