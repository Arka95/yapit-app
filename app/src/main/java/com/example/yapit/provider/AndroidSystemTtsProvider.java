package com.example.yapit.provider;

import android.content.Context;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import com.example.yapit.model.*;
import org.json.JSONObject;
import java.io.File;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public final class AndroidSystemTtsProvider implements ModelProvider {
    @Override public String providerId() { return "android-system"; }
    @Override public boolean isAvailable(Context context, JSONObject config) { return true; }
    @Override public List<ModelMetadata> discoverModels(Context context, JSONObject config) { return ConfiguredModelParser.parse(providerId(), config); }

    @Override public ModelSession openSession(Context context, JSONObject config, ModelMetadata model, ProgressSink progress) throws Exception {
        progress.onStage(GenerationStatus.LOADING_MODEL, "Loading device TTS engine");
        CountDownLatch initLatch = new CountDownLatch(1);
        AtomicReference<Integer> initStatus = new AtomicReference<>(TextToSpeech.ERROR);
        TextToSpeech tts = new TextToSpeech(context.getApplicationContext(), status -> { initStatus.set(status); initLatch.countDown(); });
        if (!initLatch.await(20, TimeUnit.SECONDS) || initStatus.get() != TextToSpeech.SUCCESS) {
            tts.shutdown(); throw new IllegalStateException("Android TTS engine failed to initialize");
        }
        tts.setLanguage(Locale.getDefault());
        return new Session(tts);
    }

    private static final class Session implements ModelSession {
        private final TextToSpeech tts;
        Session(TextToSpeech tts) { this.tts = tts; }
        @Override public EnrollmentResult enroll(VoiceProfile profile, File sample, ProgressSink progress) { return EnrollmentResult.notRequired(); }
        @Override public GenerationResult generate(VoiceProfile profile, String enrollmentId, GenerationRecord request,
                File destination, ProgressSink progress) throws Exception {
            progress.onStage(GenerationStatus.GENERATING, "Synthesizing with device TTS");
            CountDownLatch done = new CountDownLatch(1); AtomicReference<String> error = new AtomicReference<>();
            String utteranceId = UUID.randomUUID().toString();
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override public void onStart(String id) { }
                @Override public void onDone(String id) { if (utteranceId.equals(id)) done.countDown(); }
                @Override public void onError(String id) { if (utteranceId.equals(id)) { error.set("Device TTS synthesis failed"); done.countDown(); } }
                @Override public void onError(String id, int code) { if (utteranceId.equals(id)) { error.set("Device TTS synthesis failed: " + code); done.countDown(); } }
            });
            int accepted = tts.synthesizeToFile(request.text, new Bundle(), destination, utteranceId);
            if (accepted != TextToSpeech.SUCCESS) throw new IllegalStateException("Device TTS rejected the generation request");
            if (!done.await(10, TimeUnit.MINUTES)) throw new IllegalStateException("Device TTS timed out");
            if (error.get() != null) throw new IllegalStateException(error.get());
            if (!destination.isFile() || destination.length() == 0) throw new IllegalStateException("Device TTS returned no audio");
            return new GenerationResult(destination, "audio/wav");
        }
        @Override public void close() { tts.shutdown(); }
    }
}
