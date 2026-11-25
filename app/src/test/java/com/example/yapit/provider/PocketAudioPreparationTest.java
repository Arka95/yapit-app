package com.example.yapit.provider;

import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.*;

/** Checks inference-only prompt trimming and the current checkpoint's input preparation. */
public final class PocketAudioPreparationTest {
    @Test public void trimsTrailingSilenceAndFadesIntoShortPauseWithoutChangingRecording() {
        float[] recording = new float[2000];
        Arrays.fill(recording, 0, 1000, 0.25f);
        float[] original = recording.clone();
        float[] prepared = PocketAudioPreparation.reference(recording, 1000, 12);
        assertEquals(1080, prepared.length);
        assertEquals(0.25f, prepared[979], 0);
        assertEquals(0, prepared[999], 0);
        assertEquals(0, prepared[1079], 0);
        assertArrayEquals(original, recording, 0);
    }

    @Test public void capsReferenceBeforeExaminingLaterAudio() {
        float[] recording = new float[20000];
        Arrays.fill(recording, 0, 12000, 0.1f);
        Arrays.fill(recording, 12000, recording.length, Float.NaN);
        float[] prepared = PocketAudioPreparation.reference(recording, 1000, 12);
        assertEquals(12080, prepared.length);
        assertEquals(0.1f, prepared[0], 0);
        assertEquals(0, prepared[12079], 0);
    }

    @Test public void rejectsSilentReferenceInsteadOfPretendingToCloneIt() {
        assertThrows(IllegalArgumentException.class,
                () -> PocketAudioPreparation.reference(new float[1000], 1000, 12));
    }

    @Test public void preparesTextWithoutLosingApostrophesOrExistingPunctuation() {
        assertEquals("It's ready.", PocketAudioPreparation.text(" \nit\u2019s   ready "));
        assertEquals("Are you ready?", PocketAudioPreparation.text("are you ready?"));
    }
}
