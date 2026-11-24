package com.example.yapit.store;

import com.example.yapit.model.VoiceProfile;
import com.example.yapit.util.IoUtils;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.nio.file.Files;
import java.util.UUID;
import static org.junit.Assert.*;

/** Exercises profile isolation, validation and migration using temporary app-private storage. */
public final class VoiceProfileStoreTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test public void namedProfilesKeepIndependentSamplesAndReloadById() throws Exception {
        VoiceProfileStore store = new VoiceProfileStore(temporary.getRoot());
        VoiceProfile first = store.create("First voice"), second = store.create("Second voice");
        Files.write(store.sampleFile(first).toPath(), new byte[]{1, 2, 3});
        Files.write(store.sampleFile(second).toPath(), new byte[]{4, 5, 6});
        VoiceProfileStore restarted = new VoiceProfileStore(temporary.getRoot());
        VoiceProfile loaded = restarted.get(first.profileId);
        assertEquals(2, restarted.list().size());
        assertEquals("First voice", loaded.displayName);
        assertEquals("First voice.sample", restarted.sampleFile(loaded).getName());
        assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(restarted.sampleFile(loaded).toPath()));
        assertArrayEquals(new byte[]{4, 5, 6}, Files.readAllBytes(restarted.sampleFile(restarted.get(second.profileId)).toPath()));
    }

    @Test public void migratingOriginalProfilePreservesRecordingIdAndEnrollment() throws Exception {
        File legacy = new File(temporary.getRoot(), "profile");
        assertTrue(legacy.mkdirs());
        VoiceProfile original = new VoiceProfile();
        original.profileId = UUID.randomUUID().toString(); original.displayName = "My voice";
        original.sampleFileName = "voice_sample.wav";
        original.enrollments.put("provider:model", new org.json.JSONObject().put("enrollment_id", "existing"));
        IoUtils.writeJsonAtomic(new File(legacy, "profile.json"), original.toJson());
        Files.write(new File(legacy, original.sampleFileName).toPath(), new byte[]{7, 8, 9});
        VoiceProfileStore store = new VoiceProfileStore(temporary.getRoot());
        VoiceProfile migrated = store.getOrCreate();
        assertEquals(original.profileId, migrated.profileId);
        assertEquals("existing", migrated.enrollmentIdFor("provider:model"));
        assertEquals("My voice.sample", migrated.sampleFileName);
        assertArrayEquals(new byte[]{7, 8, 9}, Files.readAllBytes(store.sampleFile(migrated).toPath()));
        Files.write(store.sampleFile(migrated).toPath(), new byte[]{10});
        assertArrayEquals(new byte[]{10}, Files.readAllBytes(store.sampleFile(store.getOrCreate()).toPath()));
        assertTrue(new File(legacy, original.sampleFileName).isFile());
        assertEquals(1, store.list().size());
    }

    @Test public void invalidNamesAreRejectedWithoutCreatingExtraProfiles() throws Exception {
        VoiceProfileStore store = new VoiceProfileStore(temporary.getRoot());
        store.create("Reader");
        assertThrows(IllegalArgumentException.class, () -> store.create("   "));
        assertThrows(IllegalArgumentException.class, () -> store.create("reader"));
        assertThrows(IllegalArgumentException.class, () -> store.create("a".repeat(65)));
        assertEquals(1, store.list().size());
    }

    @Test public void profileNamesCannotPlaceSamplesOutsideTheirOwnDirectory() throws Exception {
        VoiceProfileStore store = new VoiceProfileStore(temporary.getRoot());
        VoiceProfile profile = store.create("../../Other");
        File expectedDirectory = new File(new File(temporary.getRoot(), "profiles"), profile.profileId);
        assertEquals(expectedDirectory.getCanonicalPath(), store.sampleFile(profile).getParentFile().getCanonicalPath());
        store.saveEnrollment(profile, "provider:model", "first-enrollment");
        VoiceProfile other = store.create("Another");
        store.saveEnrollment(other, "provider:model", "second-enrollment");
        store.invalidateEnrollments(profile);
        assertEquals("", store.get(profile.profileId).enrollmentIdFor("provider:model"));
        assertEquals("second-enrollment", store.get(other.profileId).enrollmentIdFor("provider:model"));
    }
}
