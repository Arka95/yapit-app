package com.example.yapit.provider;

import com.example.yapit.model.GenerationStatus;
import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

/** Verifies the exact callback entry point resolved by sherpa-onnx JNI without loading a model. */
public final class PocketTtsCallbackTest {
    @Test public void nativeFloatArrayEntryPointUpdatesProgress() throws Exception {
        List<GenerationStatus> stages = new ArrayList<>();
        PocketTtsProvider.GenerationCallback callback = new PocketTtsProvider.GenerationCallback(
                (stage, message) -> stages.add(stage), "Reader");
        Object result = callback.getClass().getMethod("invoke", float[].class).invoke(callback, (Object) new float[]{0.1f});
        assertEquals(1, result);
        assertEquals(List.of(GenerationStatus.GENERATING), stages);
        callback.throwIfFailed();
    }

    @Test public void cancelledCallbackStopsNativeGenerationAndPreservesFailure() throws Exception {
        InterruptedException cancelled = new InterruptedException("Cancelled");
        PocketTtsProvider.GenerationCallback callback = new PocketTtsProvider.GenerationCallback(
                (stage, message) -> { throw cancelled; }, "Reader");
        assertEquals(Integer.valueOf(0), callback.invoke(new float[0]));
        assertSame(cancelled, assertThrows(InterruptedException.class, callback::throwIfFailed));
    }
}
