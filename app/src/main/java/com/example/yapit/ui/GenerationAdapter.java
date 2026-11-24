package com.example.yapit.ui;

import android.graphics.drawable.AnimationDrawable;
import android.view.*;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import androidx.work.WorkInfo;
import com.example.yapit.R;
import com.example.yapit.model.*;
import com.google.android.material.button.MaterialButton;
import java.text.DateFormat;
import java.util.*;

public final class GenerationAdapter extends RecyclerView.Adapter<GenerationAdapter.Holder> {
    public interface Listener {
        void onViewText(GenerationRecord record);
        void onPlay(GenerationRecord record);
        void onOpenFolder(GenerationRecord record);
        void onShare(GenerationRecord record);
        void onDelete(GenerationRecord record);
    }
    private final List<GenerationRecord> items = new ArrayList<>();
    private final Listener listener;
    private final Map<String, WorkInfo.State> workStates = new HashMap<>();
    private String playingGenerationId;
    private boolean playing;
    public GenerationAdapter(Listener listener) { this.listener = listener; }
    public void submit(List<GenerationRecord> records, Map<String, WorkInfo.State> states) {
        items.clear(); items.addAll(records); workStates.clear(); workStates.putAll(states); notifyDataSetChanged();
    }
    public void setPlayback(String generationId, boolean isPlaying) {
        String previousId = playingGenerationId;
        playingGenerationId = generationId; playing = isPlaying;
        for (int position = 0; position < items.size(); position++)
            if (Objects.equals(items.get(position).generationId, previousId)
                    || Objects.equals(items.get(position).generationId, generationId)) notifyItemChanged(position, "playback");
    }
    public void refreshTiming() {
        for (int position = 0; position < items.size(); position++) {
            GenerationRecord record = items.get(position);
            if (workStates.get(record.generationId) == WorkInfo.State.RUNNING
                    && record.status != GenerationStatus.COMPLETED
                    && record.status != GenerationStatus.FAILED
                    && record.status != GenerationStatus.CANCELLED) notifyItemChanged(position, "timing");
        }
    }

    @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new Holder(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_generation, parent, false));
    }
    @Override public void onBindViewHolder(@NonNull Holder h, int position) {
        GenerationRecord r = items.get(position);
        String shortId = r.generationId.length() > 8 ? r.generationId.substring(0, 8) : r.generationId;
        h.id.setText("#" + shortId + " · " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(new Date(r.createdAt)));
        h.preview.setText(r.status == GenerationStatus.FAILED && !r.error.isBlank() ? r.text + "\n\n" + r.error : r.text);
        h.preview.setOnClickListener(v -> listener.onViewText(r));
        h.model.setText(r.modelName + " · " + r.providerId + "/" + r.modelId + " · v" + r.modelVersion);
        updateTiming(h, r);
        boolean pending = r.status != GenerationStatus.COMPLETED && r.status != GenerationStatus.FAILED && r.status != GenerationStatus.CANCELLED;
        WorkInfo.State state = workStates.get(r.generationId);
        pending &= state == null || !state.isFinished();
        h.progress.setVisibility(pending ? View.VISIBLE : View.GONE);
        AnimationDrawable animation = (AnimationDrawable) h.progress.getDrawable();
        if (pending) animation.start(); else animation.stop();
        h.actions.setVisibility(pending ? View.GONE : View.VISIBLE);
        int audioVisibility = r.status == GenerationStatus.COMPLETED ? View.VISIBLE : View.GONE;
        h.play.setVisibility(audioVisibility); h.openFolder.setVisibility(audioVisibility); h.share.setVisibility(audioVisibility);
        updatePlayback(h, r);
        h.play.setOnClickListener(v -> listener.onPlay(r));
        h.openFolder.setOnClickListener(v -> listener.onOpenFolder(r)); h.share.setOnClickListener(v -> listener.onShare(r));
        h.delete.setOnClickListener(v -> listener.onDelete(r));
    }
    @Override public void onBindViewHolder(@NonNull Holder h, int position, @NonNull List<Object> payloads) {
        if (payloads.isEmpty()) onBindViewHolder(h, position);
        else {
            if (payloads.contains("timing")) updateTiming(h, items.get(position));
            if (payloads.contains("playback")) updatePlayback(h, items.get(position));
        }
    }
    private void updatePlayback(Holder h, GenerationRecord record) {
        boolean active = playing && record.generationId.equals(playingGenerationId);
        h.play.setIconResource(active ? R.drawable.ic_pause : R.drawable.ic_play);
        String label = h.play.getContext().getString(active ? R.string.pause : R.string.play);
        h.play.setContentDescription(label); h.play.setTooltipText(label);
    }
    private void updateTiming(Holder h, GenerationRecord r) {
        WorkInfo.State state = workStates.get(r.generationId);
        boolean pending = r.status != GenerationStatus.COMPLETED && r.status != GenerationStatus.FAILED && r.status != GenerationStatus.CANCELLED;
        String status = label(r.status), detail = "";
        long now = System.currentTimeMillis();
        if (pending && state != WorkInfo.State.RUNNING) {
            status = state == WorkInfo.State.FAILED ? "FAILED" : state == WorkInfo.State.CANCELLED ? "CANCELLED" : "WAITING";
            detail = state != null && state.isFinished() ? "The background worker has stopped." : "Waiting for Android to start the worker.";
        } else if (pending) {
            double elapsed = r.processingStartedAt > 0 ? Math.max(0, now - r.processingStartedAt) / 1000.0 : 0;
            long lastUpdate = Math.max(0, now - r.updatedAt) / 1000;
            detail = r.progressMessage + String.format(Locale.US, "\nRunning · %.1f s processing · last progress %d s ago", elapsed, lastUpdate);
        } else if (r.processingCompletedAt >= r.processingStartedAt && r.processingStartedAt > 0) {
            detail = String.format(Locale.US, "%.1f s processing", (r.processingCompletedAt - r.processingStartedAt) / 1000.0);
        }
        h.status.setText(status); h.details.setText(detail); h.details.setVisibility(detail.isEmpty() ? View.GONE : View.VISIBLE);
    }
    @Override public int getItemCount() { return items.size(); }
    @Override public void onViewAttachedToWindow(@NonNull Holder h) {
        super.onViewAttachedToWindow(h);
        if (h.progress.getVisibility() == View.VISIBLE) ((AnimationDrawable) h.progress.getDrawable()).start();
    }
    @Override public void onViewDetachedFromWindow(@NonNull Holder h) {
        ((AnimationDrawable) h.progress.getDrawable()).stop();
        super.onViewDetachedFromWindow(h);
    }
    private static String label(GenerationStatus s) {
        return switch (s) {
            case QUEUED -> "QUEUED"; case LOADING_MODEL -> "LOADING MODEL"; case ENROLLING -> "ENROLLING";
            case GENERATING -> "GENERATING"; case COMPLETED -> "READY"; case FAILED -> "FAILED"; case CANCELLED -> "CANCELLED";
        };
    }
    static final class Holder extends RecyclerView.ViewHolder {
        final TextView id, status, preview, model, details; final ImageView progress; final Button openFolder, share, delete;
        final MaterialButton play;
        final View actions;
        Holder(View v) { super(v); id = v.findViewById(R.id.generationId); status = v.findViewById(R.id.status);
            preview = v.findViewById(R.id.textPreview); model = v.findViewById(R.id.modelName);
            progress = v.findViewById(R.id.progress); play = v.findViewById(R.id.playButton);
            details = v.findViewById(R.id.processingDetails); actions = v.findViewById(R.id.audioActions);
            openFolder = v.findViewById(R.id.openFolderButton); share = v.findViewById(R.id.shareButton);
            delete = v.findViewById(R.id.deleteGenerationButton); }
    }
}
