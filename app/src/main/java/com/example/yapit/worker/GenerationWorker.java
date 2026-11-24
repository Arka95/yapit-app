package com.example.yapit.worker;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.os.Build;
import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.work.*;
import com.example.yapit.R;
import com.example.yapit.YapitApp;
import com.example.yapit.model.*;
import com.example.yapit.provider.*;
import com.example.yapit.store.*;
import com.example.yapit.ui.MainActivity;
import java.io.File;

/** Engine-agnostic orchestration. Provider-specific code never owns jobs, storage, UI, or notifications. */
public final class GenerationWorker extends Worker {
    public static final String KEY_GENERATION_ID = "generation_id";
    private GenerationStore generations;
    private String generationId;

    public GenerationWorker(@NonNull Context context, @NonNull WorkerParameters params) { super(context, params); }

    @NonNull @Override public Result doWork() {
        generationId = getInputData().getString(KEY_GENERATION_ID);
        if (generationId == null) return Result.failure();
        generations = new GenerationStore(getApplicationContext());
        setForegroundAsync(foreground("Preparing model…"));
        try {
            GenerationRecord record = generations.get(generationId);
            record.processingStartedAt = System.currentTimeMillis(); record.processingCompletedAt = 0; generations.save(record);
            stage(GenerationStatus.LOADING_MODEL, "Loading " + record.modelName);
            ModelRegistry registry = ModelRegistry.load(getApplicationContext());
            ModelMetadata model = registry.requireModel(record.providerId, record.modelId);
            ProviderRegistration registration = registry.requireProvider(record.providerId);
            VoiceProfileStore profiles = new VoiceProfileStore(getApplicationContext());
            VoiceProfile profile = profiles.get(record.profileId);

            File destination = generations.audioFile(generationId);
            File partial = new File(destination.getParentFile(), "audio.partial");
            if (partial.exists()) partial.delete();
            GenerationResult result;
            try (ModelSession session = registration.provider.openSession(getApplicationContext(), registration.config, model, this::stage)) {
                String enrollmentId = profile.enrollmentIdFor(model.stableKey());
                if (model.supports(ModelCapability.VOICE_ENROLLMENT) && enrollmentId.isBlank()) {
                    EnrollmentResult enrolled = session.enroll(profile, profiles.sampleFile(profile), this::stage);
                    enrollmentId = enrolled.enrollmentId;
                    profiles.saveEnrollment(profile, model.stableKey(), enrollmentId);
                }
                result = session.generate(profile, enrollmentId, record, partial, this::stage);
            }
            if (!result.audioFile.equals(partial)) throw new IllegalStateException("Provider must write to the requested destination");
            if (destination.exists()) destination.delete();
            if (!partial.renameTo(destination)) throw new IllegalStateException("Could not commit generated audio");
            record = generations.get(generationId);
            try { new GeneratedAudioFiles(getApplicationContext()).publish(record); } catch (Exception ignored) { }
            record.status = GenerationStatus.COMPLETED;
            record.processingCompletedAt = System.currentTimeMillis(); record.progressMessage = "";
            record.audioFileName = destination.getName(); record.mimeType = result.mimeType; record.error = ""; generations.save(record);
            setProgressAsync(new Data.Builder().putString("status", GenerationStatus.COMPLETED.name()).build());
            notifyFinished(record, null);
            return Result.success();
        } catch (Exception e) {
            try { generations.updateStatus(generationId, GenerationStatus.FAILED, readable(e)); } catch (Exception ignored) { }
            notifyFinished(null, readable(e));
            return Result.failure(new Data.Builder().putString("error", readable(e)).build());
        }
    }

    private void stage(GenerationStatus status, String message) throws Exception {
        if (isStopped()) throw new InterruptedException("Generation cancelled");
        generations.updateStatus(generationId, status, "", message);
        setProgressAsync(new Data.Builder().putString("status", status.name()).putString("message", message).build());
        setForegroundAsync(foreground(message));
    }

    private ForegroundInfo foreground(String message) {
        Notification n = new NotificationCompat.Builder(getApplicationContext(), YapitApp.GENERATION_CHANNEL)
                .setSmallIcon(R.drawable.ic_wave).setContentTitle("Generating speech").setContentText(message)
                .setOngoing(true).setOnlyAlertOnce(true).setProgress(0, 0, true).setContentIntent(openAppIntent()).build();
        int id = notificationId();
        return Build.VERSION.SDK_INT >= 29 ? new ForegroundInfo(id, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) : new ForegroundInfo(id, n);
    }

    private void notifyFinished(GenerationRecord record, String error) {
        boolean ok = error == null;
        Notification n = new NotificationCompat.Builder(getApplicationContext(), YapitApp.GENERATION_CHANNEL)
                .setSmallIcon(R.drawable.ic_wave).setContentTitle(ok ? "Speech is ready" : "Generation failed")
                .setContentText(ok ? record.text : error).setAutoCancel(true).setContentIntent(openAppIntent())
                .setPriority(NotificationCompat.PRIORITY_DEFAULT).build();
        getApplicationContext().getSystemService(NotificationManager.class).notify(notificationId(), n);
    }

    private PendingIntent openAppIntent() {
        Intent intent = new Intent(getApplicationContext(), MainActivity.class).putExtra("open_generated", true)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(getApplicationContext(), notificationId(), intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
    private int notificationId() { return 1000 + Math.abs(generationId.hashCode() % 100000); }
    private static String readable(Exception e) { return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(); }
}
