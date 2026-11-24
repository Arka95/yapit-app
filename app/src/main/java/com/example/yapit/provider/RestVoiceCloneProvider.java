package com.example.yapit.provider;

import android.content.Context;
import com.example.yapit.model.*;
import org.json.JSONObject;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.UUID;

/** Reference adapter for a synchronous REST API. Replace or subclass this adapter for your service. */
public final class RestVoiceCloneProvider implements ModelProvider {
    @Override public String providerId() { return "rest-voice-clone"; }
    @Override public boolean isAvailable(Context context, JSONObject config) { return !config.optString("base_url").isBlank(); }
    @Override public List<ModelMetadata> discoverModels(Context context, JSONObject config) { return ConfiguredModelParser.parse(providerId(), config); }

    @Override public ModelSession openSession(Context context, JSONObject config, ModelMetadata model, ProgressSink progress) throws Exception {
        progress.onStage(GenerationStatus.LOADING_MODEL, "Preparing " + model.displayName);
        return new ModelSession() {
            @Override public EnrollmentResult enroll(VoiceProfile profile, File sample, ProgressSink sink) throws Exception {
                return enrollInternal(context.getApplicationContext(), config, model, profile, sample, sink);
            }
            @Override public GenerationResult generate(VoiceProfile profile, String enrollmentId, GenerationRecord request, File destination, ProgressSink sink) throws Exception {
                return generateInternal(context.getApplicationContext(), config, model, profile, enrollmentId, request, destination, sink);
            }
        };
    }

    private static EnrollmentResult enrollInternal(Context context, JSONObject config, ModelMetadata model,
            VoiceProfile profile, File sample, ProgressSink progress) throws Exception {
        if (sample == null || !sample.isFile()) throw new IllegalStateException("Record a voice sample before using " + model.displayName);
        progress.onStage(GenerationStatus.ENROLLING, "Enrolling voice profile");
        String boundary = "YapIt-" + UUID.randomUUID();
        HttpURLConnection c = open(config, "/v1/profiles/enroll", "POST");
        c.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        c.setDoOutput(true);
        try (OutputStream out = new BufferedOutputStream(c.getOutputStream())) {
            field(out, boundary, "profile_id", profile.profileId);
            field(out, boundary, "display_name", profile.displayName);
            field(out, boundary, "model_id", model.modelId);
            file(out, boundary, "sample", sample, "audio/wav");
            write(out, "--" + boundary + "--\r\n");
        }
        int code = c.getResponseCode();
        String body = readText(code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream());
        c.disconnect();
        if (code < 200 || code >= 300) throw new IOException("Enrollment HTTP " + code + ": " + body);
        String enrollmentId = new JSONObject(body).optString("enrollment_id", profile.profileId);
        return new EnrollmentResult(enrollmentId);
    }

    private static GenerationResult generateInternal(Context context, JSONObject config, ModelMetadata model,
            VoiceProfile profile, String enrollmentId, GenerationRecord request, File destination, ProgressSink progress) throws Exception {
        progress.onStage(GenerationStatus.GENERATING, "Generating speech");
        HttpURLConnection c = open(config, "/v1/generations", "POST");
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        c.setRequestProperty("Accept", "audio/*");
        c.setDoOutput(true);
        JSONObject payload = new JSONObject().put("generation_id", request.generationId)
                .put("profile_id", profile.profileId).put("enrollment_id", enrollmentId)
                .put("model_id", model.modelId).put("text", request.text);
        try (OutputStream out = c.getOutputStream()) { out.write(payload.toString().getBytes(StandardCharsets.UTF_8)); }
        int code = c.getResponseCode();
        if (code < 200 || code >= 300) {
            String body = readText(c.getErrorStream()); c.disconnect();
            throw new IOException("Generation HTTP " + code + ": " + body);
        }
        String mime = c.getContentType();
        try (InputStream in = c.getInputStream(); OutputStream out = new BufferedOutputStream(new FileOutputStream(destination))) {
            byte[] buffer = new byte[8192]; int n; while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
        } finally { c.disconnect(); }
        if (!destination.isFile() || destination.length() == 0) throw new IOException("Generation API returned no audio");
        return new GenerationResult(destination, mime == null ? "audio/wav" : mime);
    }

    private static HttpURLConnection open(JSONObject config, String path, String method) throws Exception {
        String base = config.getString("base_url").replaceAll("/+$", "");
        HttpURLConnection c = (HttpURLConnection) new URL(base + path).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(config.optInt("connect_timeout_seconds", 30) * 1000);
        c.setReadTimeout(config.optInt("read_timeout_seconds", 600) * 1000);
        String key = config.optString("api_key"); if (!key.isBlank()) c.setRequestProperty("Authorization", "Bearer " + key);
        return c;
    }

    private static void field(OutputStream out, String boundary, String name, String value) throws IOException {
        write(out, "--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n" + value + "\r\n");
    }
    private static void file(OutputStream out, String boundary, String name, File file, String mime) throws IOException {
        write(out, "--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"; filename=\"sample.wav\"\r\nContent-Type: " + mime + "\r\n\r\n");
        Files.copy(file.toPath(), out); write(out, "\r\n");
    }
    private static void write(OutputStream out, String s) throws IOException { out.write(s.getBytes(StandardCharsets.UTF_8)); }
    private static String readText(InputStream in) throws IOException {
        if (in == null) return ""; try (InputStream input = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] b = new byte[4096]; int n; while ((n = input.read(b)) != -1 && out.size() < 65536) out.write(b, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
