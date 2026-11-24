package com.example.yapit.store;

import com.example.yapit.model.*;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.nio.file.Files;
import java.util.EnumSet;
import static org.junit.Assert.*;

/** Verifies deleting an output cannot remove another generation or a voice profile's recording. */
public final class GenerationStoreTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test public void deletingGenerationRemovesItsAudioAndHistoryOnly() throws Exception {
        VoiceProfileStore profiles = new VoiceProfileStore(temporary.getRoot());
        VoiceProfile profile = profiles.create("Reader");
        Files.write(profiles.sampleFile(profile).toPath(), new byte[]{1, 2, 3});
        GenerationStore store = new GenerationStore(temporary.getRoot());
        ModelMetadata model = new ModelMetadata("test", "model", "Test", "1", "", EnumSet.noneOf(ModelCapability.class));
        GenerationRecord first = store.create("First", profile, model), second = store.create("Second", profile, model);
        Files.write(store.audioFile(first.generationId).toPath(), new byte[]{4, 5});
        Files.write(store.audioFile(second.generationId).toPath(), new byte[]{6, 7});
        store.delete(first.generationId);
        assertFalse(store.audioFile(first.generationId).exists());
        assertEquals(1, store.list().size());
        assertEquals(second.generationId, store.list().get(0).generationId);
        assertArrayEquals(new byte[]{6, 7}, Files.readAllBytes(store.audioFile(second.generationId).toPath()));
        assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(profiles.sampleFile(profiles.get(profile.profileId)).toPath()));
    }

    @Test public void deletingAnInvalidGenerationIdCannotEscapeStorage() throws Exception {
        GenerationStore store = new GenerationStore(temporary.getRoot());
        java.io.File protectedFile = temporary.newFile("keep.sample");
        assertThrows(IllegalArgumentException.class, () -> store.delete("../"));
        assertTrue(protectedFile.isFile());
    }
}
