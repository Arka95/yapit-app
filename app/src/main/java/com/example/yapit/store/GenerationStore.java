package com.example.yapit.store;

import android.content.Context;
import com.example.yapit.model.*;
import com.example.yapit.util.IoUtils;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.*;

public final class GenerationStore {
    private final File root;
    public GenerationStore(Context context) { this(context.getFilesDir()); }
    public GenerationStore(File filesDirectory) { root = new File(filesDirectory, "generations"); }

    public synchronized GenerationRecord create(String text, VoiceProfile profile, ModelMetadata model) throws Exception {
        GenerationRecord r = new GenerationRecord(); r.generationId = UUID.randomUUID().toString(); r.profileId = profile.profileId;
        r.text = text; r.providerId = model.providerId; r.modelId = model.modelId; r.modelName = model.displayName;
        r.modelVersion = model.version; r.status = GenerationStatus.QUEUED; r.createdAt = r.updatedAt = System.currentTimeMillis();
        r.audioFileName = "audio.wav"; save(r); return r;
    }
    public synchronized GenerationRecord get(String id) throws Exception { return GenerationRecord.fromJson(IoUtils.readJson(metadataFile(id))); }
    public synchronized void save(GenerationRecord r) throws Exception { r.updatedAt = System.currentTimeMillis(); IoUtils.writeJsonAtomic(metadataFile(r.generationId), r.toJson()); }
    public synchronized void updateStatus(String id, GenerationStatus status, String error) throws Exception {
        updateStatus(id, status, error, "");
    }
    public synchronized void updateStatus(String id, GenerationStatus status, String error, String progressMessage) throws Exception {
        GenerationRecord r = get(id); r.status = status; r.error = error == null ? "" : error; r.progressMessage = progressMessage;
        if (status == GenerationStatus.FAILED || status == GenerationStatus.CANCELLED) r.processingCompletedAt = System.currentTimeMillis();
        save(r);
    }
    public File audioFile(String id) { return new File(directory(id), "audio.wav"); }
    public synchronized void delete(String id) throws IOException {
        File directory = directory(id);
        File[] files = directory.listFiles();
        if (files != null) for (File file : files) {
            if (!file.isFile()) throw new IOException("Generation contains an unexpected directory");
            Files.delete(file.toPath());
        }
        Files.deleteIfExists(directory.toPath());
    }
    public synchronized List<GenerationRecord> list() {
        List<GenerationRecord> result = new ArrayList<>(); File[] dirs = root.listFiles(File::isDirectory);
        if (dirs != null) for (File dir : dirs) try { File m = new File(dir, "metadata.json"); if (m.isFile()) result.add(GenerationRecord.fromJson(IoUtils.readJson(m))); } catch (Exception ignored) { }
        result.sort((a, b) -> Long.compare(b.createdAt, a.createdAt)); return result;
    }
    private File directory(String id) { return new File(root, UUID.fromString(id).toString()); }
    private File metadataFile(String id) { return new File(directory(id), "metadata.json"); }
}
