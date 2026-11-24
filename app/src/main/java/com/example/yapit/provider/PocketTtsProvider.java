package com.example.yapit.provider;

import android.content.Context;
import com.example.yapit.model.*;
import com.example.yapit.store.VoiceProfileStore;
import com.k2fsa.sherpa.onnx.*;
import kotlin.jvm.functions.Function1;
import org.json.JSONObject;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;

/** Runs bundled Pocket TTS on the CPU with the selected profile's reference recording. */
public final class PocketTtsProvider implements ModelProvider {
    @Override public String providerId() { return "pocket-tts"; }
    @Override public boolean isAvailable(Context context, JSONObject config) { return true; }
    @Override public List<ModelMetadata> discoverModels(Context context, JSONObject config) {
        return ConfiguredModelParser.parse(providerId(), config);
    }

    @Override public ModelSession openSession(Context context, JSONObject config, ModelMetadata model, ProgressSink progress) throws Exception {
        progress.onStage(GenerationStatus.LOADING_MODEL, "Preparing offline voice-cloning model");
        File directory = prepareModel(context, config.getString("asset_directory"));
        OfflineTtsPocketModelConfig pocket = new OfflineTtsPocketModelConfig();
        pocket.setLmFlow(new File(directory, "lm_flow.int8.onnx").getAbsolutePath());
        pocket.setLmMain(new File(directory, "lm_main.int8.onnx").getAbsolutePath());
        pocket.setEncoder(new File(directory, "encoder.onnx").getAbsolutePath());
        pocket.setDecoder(new File(directory, "decoder.int8.onnx").getAbsolutePath());
        pocket.setTextConditioner(new File(directory, "text_conditioner.onnx").getAbsolutePath());
        pocket.setVocabJson(new File(directory, "vocab.json").getAbsolutePath());
        pocket.setTokenScoresJson(new File(directory, "token_scores.json").getAbsolutePath());
        OfflineTtsModelConfig modelConfig = new OfflineTtsModelConfig();
        modelConfig.setPocket(pocket); modelConfig.setNumThreads(config.optInt("num_threads", 2)); modelConfig.setProvider("cpu");
        OfflineTtsConfig ttsConfig = new OfflineTtsConfig(); ttsConfig.setModel(modelConfig);
        try {
            return new Session(new OfflineTts(null, ttsConfig), new VoiceProfileStore(context), config.optInt("steps", 5));
        } catch (LinkageError error) {
            throw new IOException("The bundled voice-cloning runtime could not be loaded", error);
        }
    }

    private static synchronized File prepareModel(Context context, String assetDirectory) throws IOException {
        File directory = new File(context.getFilesDir(), assetDirectory);
        Files.createDirectories(directory.toPath());
        for (String name : context.getAssets().list(assetDirectory)) {
            File target = new File(directory, name);
            if (target.isFile() && target.length() > 0) continue;
            File pending = new File(directory, name + ".pending");
            try (InputStream input = context.getAssets().open(assetDirectory + "/" + name)) {
                Files.copy(input, pending.toPath(), StandardCopyOption.REPLACE_EXISTING);
                Files.move(pending.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            } finally { Files.deleteIfExists(pending.toPath()); }
        }
        return directory;
    }

    private static final class Session implements ModelSession {
        private final OfflineTts tts;
        private final VoiceProfileStore profiles;
        private final int steps;
        Session(OfflineTts tts, VoiceProfileStore profiles, int steps) { this.tts = tts; this.profiles = profiles; this.steps = steps; }
        @Override public EnrollmentResult enroll(VoiceProfile profile, File sample, ProgressSink progress) throws Exception {
            if (!sample.isFile()) throw new FileNotFoundException("Record a sample for " + profile.displayName);
            return new EnrollmentResult(profile.profileId);
        }
        @Override public GenerationResult generate(VoiceProfile profile, String enrollmentId, GenerationRecord request, File destination, ProgressSink progress) throws Exception {
            WaveData reference = WaveReader.Companion.readWave(profiles.sampleFile(profile).getAbsolutePath());
            if (reference.getSamples().length == 0) throw new IOException("The selected voice sample contains no audio");
            GenerationConfig generation = new GenerationConfig();
            generation.setReferenceAudio(reference.getSamples()); generation.setReferenceSampleRate(reference.getSampleRate());
            generation.setNumSteps(steps); generation.setExtra(Map.of("max_reference_audio_len", "12"));
            progress.onStage(GenerationStatus.GENERATING, "Cloning " + profile.displayName + " on device");
            GenerationCallback callback = new GenerationCallback(progress, profile.displayName);
            GeneratedAudio audio = tts.generateWithConfigAndCallback(request.text, generation, callback);
            callback.throwIfFailed();
            progress.onStage(GenerationStatus.GENERATING, "Saving cloned speech");
            if (audio.getSamples().length == 0 || !audio.save(destination.getAbsolutePath())) throw new IOException("Voice cloning returned no audio");
            return new GenerationResult(destination, "audio/wav");
        }
        @Override public void close() { tts.release(); }
    }

    // JNI resolves invoke(float[]) directly; a Java lambda exposes only invoke(Object).
    static final class GenerationCallback implements Function1<float[], Integer> {
        private final ProgressSink progress;
        private final String profileName;
        private Exception failure;
        GenerationCallback(ProgressSink progress, String profileName) { this.progress = progress; this.profileName = profileName; }
        @Override public Integer invoke(float[] samples) {
            try { progress.onStage(GenerationStatus.GENERATING, "Cloning " + profileName + " on device"); return 1; }
            catch (Exception error) { failure = error; return 0; }
        }
        void throwIfFailed() throws Exception { if (failure != null) throw failure; }
    }
}
