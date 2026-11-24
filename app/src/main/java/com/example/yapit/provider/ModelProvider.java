package com.example.yapit.provider;

import android.content.Context;
import com.example.yapit.model.ModelMetadata;
import org.json.JSONObject;
import java.util.List;

/** Provider SPI: discovery and model loading stay behind this boundary; UI/jobs never depend on an engine. */
public interface ModelProvider {
    String providerId();
    boolean isAvailable(Context context, JSONObject config);
    List<ModelMetadata> discoverModels(Context context, JSONObject config) throws Exception;
    ModelSession openSession(Context context, JSONObject config, ModelMetadata model, ProgressSink progress) throws Exception;
}
