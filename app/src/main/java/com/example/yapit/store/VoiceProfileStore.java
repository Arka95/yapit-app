package com.example.yapit.store;

import android.content.Context;
import com.example.yapit.model.VoiceProfile;
import com.example.yapit.util.IoUtils;
import org.json.JSONObject;
import java.io.File;
import java.io.FileNotFoundException;
import java.nio.file.Files;
import java.util.*;

/** Persists named profiles and their samples, including migration of the original single profile. */
public final class VoiceProfileStore {
    private final File root;
    private final File legacyDirectory;
    public VoiceProfileStore(Context context) { this(context.getFilesDir()); }
    public VoiceProfileStore(File filesDirectory) {
        root = new File(filesDirectory, "profiles");
        legacyDirectory = new File(filesDirectory, "profile");
    }

    public synchronized VoiceProfile getOrCreate() throws Exception {
        List<VoiceProfile> profiles = list();
        return profiles.isEmpty() ? create("My voice") : profiles.get(0);
    }
    public synchronized List<VoiceProfile> list() throws Exception {
        migrateLegacyProfile();
        List<VoiceProfile> profiles = new ArrayList<>();
        File[] directories = root.listFiles(File::isDirectory);
        if (directories != null) for (File directory : directories) {
            File metadata = new File(directory, "profile.json");
            if (metadata.isFile()) profiles.add(VoiceProfile.fromJson(IoUtils.readJson(metadata)));
        }
        profiles.sort(Comparator.comparingLong(p -> p.createdAt));
        return profiles;
    }
    public synchronized VoiceProfile create(String name) throws Exception {
        String displayName = name == null ? "" : name.trim();
        if (displayName.isEmpty()) throw new IllegalArgumentException("Enter a profile name");
        if (displayName.length() > 64) throw new IllegalArgumentException("Use at most 64 characters for the profile name");
        for (VoiceProfile existing : list()) if (existing.displayName.equalsIgnoreCase(displayName))
            throw new IllegalArgumentException("A profile with this name already exists");
        VoiceProfile profile = new VoiceProfile();
        profile.profileId = UUID.randomUUID().toString(); profile.displayName = displayName;
        profile.createdAt = System.currentTimeMillis();
        profile.sampleFileName = displayName.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_") + ".sample";
        save(profile); return profile;
    }
    public synchronized VoiceProfile get(String id) throws Exception {
        migrateLegacyProfile();
        File metadata = new File(profileDirectory(id), "profile.json");
        if (!metadata.isFile()) throw new FileNotFoundException("The selected voice profile no longer exists");
        return VoiceProfile.fromJson(IoUtils.readJson(metadata));
    }
    public synchronized void save(VoiceProfile profile) throws Exception {
        IoUtils.writeJsonAtomic(new File(profileDirectory(profile.profileId), "profile.json"), profile.toJson());
    }
    public File sampleFile(VoiceProfile profile) {
        return new File(profileDirectory(profile.profileId), profile.sampleFileName);
    }
    private File profileDirectory(String id) { return new File(root, UUID.fromString(id).toString()); }

    private void migrateLegacyProfile() throws Exception {
        File metadata = new File(legacyDirectory, "profile.json");
        if (!metadata.isFile()) return;
        VoiceProfile profile = VoiceProfile.fromJson(IoUtils.readJson(metadata));
        File destination = new File(profileDirectory(profile.profileId), "profile.json");
        if (destination.isFile()) return;
        File oldSample = new File(legacyDirectory, profile.sampleFileName);
        profile.sampleFileName = profile.displayName.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_") + ".sample";
        Files.createDirectories(destination.getParentFile().toPath());
        if (oldSample.isFile()) Files.copy(oldSample.toPath(), sampleFile(profile).toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        save(profile);
    }
    public synchronized void saveEnrollment(VoiceProfile p, String stableModelKey, String enrollmentId) throws Exception {
        p.enrollments.put(stableModelKey, new JSONObject().put("enrollment_id", enrollmentId).put("updated_at", System.currentTimeMillis())); save(p);
    }
    public synchronized void invalidateEnrollments(VoiceProfile p) throws Exception { p.enrollments = new JSONObject(); save(p); }
}
