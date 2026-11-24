package com.example.yapit.ui;

import android.Manifest;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.drawable.Animatable;
import android.media.MediaPlayer;
import android.os.*;
import android.text.InputFilter;
import android.view.View;
import android.view.WindowManager;
import android.widget.*;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.work.WorkManager;
import androidx.work.WorkInfo;
import com.example.yapit.R;
import com.example.yapit.audio.WavRecorder;
import com.example.yapit.model.*;
import com.example.yapit.provider.ModelRegistry;
import com.example.yapit.store.*;
import com.example.yapit.worker.GenerationScheduler;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;

public final class MainActivity extends AppCompatActivity {
    private TextView tabProfiles, tabCreate, tabGenerated, profileStatus, modelCapabilities;
    private View profilesPanel, createPanel, generatedPanel, emptyState;
    private MaterialButton recordButton, generateButton, addProfileButton, playSampleButton;
    private Spinner modelSpinner, profileSpinner, generationProfileSpinner;
    private TextInputEditText textInput;
    private GenerationAdapter adapter;
    private GeneratedAudioActions audioActions;
    private final Map<String, WorkInfo.State> workStates = new HashMap<>();
    private GenerationStore generationStore;
    private VoiceProfileStore profileStore;
    private UserPreferences preferences;
    private VoiceProfile profile;
    private List<VoiceProfile> profiles = List.of();
    private List<ModelMetadata> models = List.of();
    private final WavRecorder recorder = new WavRecorder();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable recordingTimeout = () -> { if (recorder.isRecording()) recorder.stop(); };
    private boolean recordingPending;
    private final Runnable progressTicker = new Runnable() {
        @Override public void run() {
            if (adapter != null && generatedPanel.getVisibility() == View.VISIBLE) adapter.refreshTiming();
            handler.postDelayed(this, 1000);
        }
    };
    private MediaPlayer player;
    private String playingAudioId;

    private final ActivityResultLauncher<String> microphonePermission = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(), granted -> { if (granted) startRecording(); else message("Microphone permission is required to enroll a voice sample."); });
    private final ActivityResultLauncher<String> notificationPermission = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(), granted -> { });

    @Override protected void onCreate(@Nullable Bundle state) {
        super.onCreate(state);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_main); applyWindowInsets();
        bindViews(); generationStore = new GenerationStore(this); profileStore = new VoiceProfileStore(this); preferences = new UserPreferences(this);
        try { loadProfiles(preferences.selectedProfileId()); loadModels(); }
        catch (Exception e) { fatal("Could not initialize app: " + e.getMessage()); }
        audioActions = new GeneratedAudioActions(this);
        adapter = new GenerationAdapter(new GenerationAdapter.Listener() {
            @Override public void onViewText(GenerationRecord record) { showGenerationText(record); }
            @Override public void onPlay(GenerationRecord record) { play(record); }
            @Override public void onOpenFolder(GenerationRecord record) { audioActions.openFolder(record); }
            @Override public void onShare(GenerationRecord record) { audioActions.share(record); }
            @Override public void onDelete(GenerationRecord record) {
                new AlertDialog.Builder(MainActivity.this).setTitle(R.string.delete_generated_audio)
                        .setMessage(R.string.delete_generated_audio_message).setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton(R.string.delete, (dialog, which) -> {
                            if (record.generationId.equals(playingAudioId)) stopPlayback();
                            audioActions.delete(record, MainActivity.this::refreshGenerations);
                        }).show();
            }
        });
        RecyclerView list = findViewById(R.id.generatedList);
        list.setLayoutManager(new LinearLayoutManager(this)); list.setAdapter(adapter);
        tabProfiles.setOnClickListener(v -> showPanel(profilesPanel));
        tabCreate.setOnClickListener(v -> showPanel(createPanel)); tabGenerated.setOnClickListener(v -> showPanel(generatedPanel));
        recordButton.setOnClickListener(v -> toggleRecording()); generateButton.setOnClickListener(v -> generate());
        playSampleButton.setOnClickListener(v -> playAudio("profile:" + profile.profileId, profileStore.sampleFile(profile)));
        addProfileButton.setOnClickListener(v -> addProfile());
        profileSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int position, long id) {
                if (position >= 0 && position < profiles.size()) {
                    profile = profiles.get(position); preferences.setSelectedProfileId(profile.profileId); updateProfileStatus();
                    if (playingAudioId != null && playingAudioId.startsWith("profile:")
                            && !playingAudioId.equals("profile:" + profile.profileId)) stopPlayback();
                    if (profileSpinner.getSelectedItemPosition() != position) profileSpinner.setSelection(position);
                    if (generationProfileSpinner.getSelectedItemPosition() != position) generationProfileSpinner.setSelection(position);
                }
            }
            @Override public void onNothingSelected(AdapterView<?> p) { }
        });
        generationProfileSpinner.setOnItemSelectedListener(profileSpinner.getOnItemSelectedListener());
        modelSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int position, long id) {
                if (position >= 0 && position < models.size()) { ModelMetadata m = models.get(position); preferences.setSelectedModelKey(m.stableKey()); modelCapabilities.setText(m.capabilitySummary() + "\n" + m.description); }
            }
            @Override public void onNothingSelected(AdapterView<?> p) { }
        });
        WorkManager.getInstance(this).getWorkInfosByTagLiveData("yapit-generation").observe(this, infos -> {
            workStates.clear();
            for (WorkInfo info : infos) for (String tag : info.getTags())
                if (tag.startsWith("generation:")) workStates.put(tag.substring("generation:".length()), info.getState());
            refreshGenerations();
        });
        requestNotificationPermission();
        showPanel(getIntent().getBooleanExtra("open_generated", false) ? generatedPanel : createPanel);
    }

    private void bindViews() {
        tabProfiles = findViewById(R.id.tabProfiles); profilesPanel = findViewById(R.id.profilesPanel);
        tabCreate = findViewById(R.id.tabGenerate); tabGenerated = findViewById(R.id.tabGenerated);
        createPanel = findViewById(R.id.createPanel); generatedPanel = findViewById(R.id.generatedPanel); emptyState = findViewById(R.id.emptyState);
        profileStatus = findViewById(R.id.profileStatus); modelCapabilities = findViewById(R.id.modelCapabilities);
        recordButton = findViewById(R.id.recordButton); generateButton = findViewById(R.id.generateButton);
        playSampleButton = findViewById(R.id.playSampleButton);
        modelSpinner = findViewById(R.id.modelSpinner); textInput = findViewById(R.id.textInput);
        profileSpinner = findViewById(R.id.profileSpinner); generationProfileSpinner = findViewById(R.id.generationProfileSpinner);
        addProfileButton = findViewById(R.id.addProfileButton);
    }

    private void applyWindowInsets() {
        View root = findViewById(R.id.rootLayout);
        int left = root.getPaddingLeft(), right = root.getPaddingRight();
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            Insets keyboard = insets.getInsets(WindowInsetsCompat.Type.ime());
            view.setPadding(left + bars.left, bars.top, right + bars.right, Math.max(bars.bottom, keyboard.bottom));
            return insets;
        });
        ViewCompat.requestApplyInsets(root);
    }

    private void loadProfiles(String selectedId) throws Exception {
        profileStore.getOrCreate(); profiles = profileStore.list();
        int selected = 0;
        for (int i = 0; i < profiles.size(); i++) if (profiles.get(i).profileId.equals(selectedId)) { selected = i; break; }
        profile = profiles.get(selected);
        ArrayAdapter<VoiceProfile> items = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, profiles);
        items.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        profileSpinner.setAdapter(items); profileSpinner.setSelection(selected);
        generationProfileSpinner.setAdapter(items); generationProfileSpinner.setSelection(selected);
        preferences.setSelectedProfileId(profile.profileId); updateProfileStatus();
    }

    private void addProfile() {
        TextInputLayout field = new TextInputLayout(this);
        TextInputEditText name = new TextInputEditText(field.getContext());
        name.setSingleLine(true); name.setHint(R.string.profile_name); name.setFilters(new InputFilter[]{new InputFilter.LengthFilter(64)});
        field.addView(name);
        int padding = Math.round(24 * getResources().getDisplayMetrics().density);
        field.setPadding(padding, padding, padding, 0);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(R.string.add_voice_profile).setView(field)
                .setNegativeButton(android.R.string.cancel, null).setPositiveButton(R.string.add, null).create();
        dialog.setOnShowListener(ignored -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
                try {
                    VoiceProfile created = profileStore.create(name.getText() == null ? "" : name.getText().toString());
                    loadProfiles(created.profileId); dialog.dismiss();
                } catch (Exception error) { field.setError(error.getMessage()); }
            });
            name.requestFocus();
            dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        });
        dialog.show();
    }

    private void loadModels() throws Exception {
        models = ModelRegistry.load(this).models();
        if (models.isEmpty()) throw new IllegalStateException("No model providers are available");
        ArrayAdapter<ModelMetadata> spinnerAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, models);
        spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item); modelSpinner.setAdapter(spinnerAdapter);
        String selected = preferences.selectedModelKey();
        for (int i = 0; i < models.size(); i++) if (models.get(i).stableKey().equals(selected)) { modelSpinner.setSelection(i); break; }
    }

    private void toggleRecording() {
        if (recorder.isRecording()) { recorder.stop(); return; }
        if (recordingPending) return;
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) microphonePermission.launch(Manifest.permission.RECORD_AUDIO);
        else startRecording();
    }

    private void startRecording() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            message("Microphone permission is required to enroll a voice sample.");
            return;
        }
        VoiceProfile recordingProfile = profile;
        try {
            stopPlayback();
            File target = new File(profileStore.sampleFile(recordingProfile).getParentFile(), "recording.pending.wav");
            if (target.exists()) target.delete();
            recordingPending = true; setProfileControlsEnabled(false);
            recorder.start(target, new WavRecorder.Listener() {
                @Override public void onStopped(File file, long durationMs) { runOnUiThread(() -> finishRecording(recordingProfile, file, durationMs)); }
                @Override public void onError(Exception error) { runOnUiThread(() -> { resetRecordButton(); updateProfileStatus(); message("Recording failed: " + error.getMessage()); }); }
            });
            if (!recorder.isRecording()) { resetRecordButton(); return; }
            recordButton.setText(R.string.recording_stop); recordButton.setIconResource(R.drawable.recording_animation);
            if (recordButton.getIcon() instanceof Animatable animation) animation.start();
            profileStatus.setText("Recording… tap Stop when finished");
            handler.postDelayed(recordingTimeout, 30_000);
        } catch (Exception e) { resetRecordButton(); message("Recording failed: " + e.getMessage()); }
    }

    private void finishRecording(VoiceProfile recordingProfile, File pending, long durationMs) {
        resetRecordButton();
        if (durationMs < 3_000) { pending.delete(); message("Record at least 3 seconds so the model has enough voice data."); updateProfileStatus(); return; }
        try {
            File sample = profileStore.sampleFile(recordingProfile);
            Files.move(pending.toPath(), sample.toPath(), StandardCopyOption.REPLACE_EXISTING);
            profileStore.invalidateEnrollments(recordingProfile); updateProfileStatus(); message("Voice sample saved for " + recordingProfile.displayName);
        } catch (Exception e) { message("Could not save sample: " + e.getMessage()); }
    }
    private void resetRecordButton() {
        handler.removeCallbacks(recordingTimeout); recordingPending = false;
        if (recordButton.getIcon() instanceof Animatable animation) animation.stop();
        recordButton.setIconResource(R.drawable.ic_wave);
        setProfileControlsEnabled(true); updateProfileStatus();
    }
    private void setProfileControlsEnabled(boolean enabled) {
        profileSpinner.setEnabled(enabled); generationProfileSpinner.setEnabled(enabled);
        addProfileButton.setEnabled(enabled); generateButton.setEnabled(enabled);
        playSampleButton.setEnabled(enabled && profile != null && profileStore.sampleFile(profile).isFile());
    }
    private void updateProfileStatus() {
        if (profile == null) return; File sample = profileStore.sampleFile(profile);
        profileStatus.setText(profile.displayName + (sample.isFile() ? " · " + sample.getName() + " · " + Math.max(1, sample.length() / 1024) + " KB" : " · no sample recorded"));
        playSampleButton.setEnabled(sample.isFile() && !recordingPending);
        if (!recordingPending) recordButton.setText(sample.isFile() ? R.string.retake_sample : R.string.record_sample);
    }

    private void generate() {
        String text = textInput.getText() == null ? "" : textInput.getText().toString().trim();
        if (text.isEmpty()) { textInput.setError("Enter text to generate"); return; }
        int index = modelSpinner.getSelectedItemPosition(); if (index < 0 || index >= models.size()) { message("Select a model"); return; }
        ModelMetadata model = models.get(index);
        if (model.supports(ModelCapability.VOICE_ENROLLMENT) && !profileStore.sampleFile(profile).isFile()) { message("Record your voice sample before using this voice-cloning model."); return; }
        try {
            GenerationRecord record = generationStore.create(text, profile, model);
            WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView()).hide(WindowInsetsCompat.Type.ime());
            textInput.clearFocus();
            GenerationScheduler.enqueue(this, record, model); textInput.setText(""); showPanel(generatedPanel); refreshGenerations();
        } catch (Exception e) { message("Could not queue generation: " + e.getMessage()); }
    }

    private void refreshGenerations() {
        List<GenerationRecord> records = generationStore.list(); adapter.submit(records, workStates); emptyState.setVisibility(records.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void showPanel(View selectedPanel) {
        View[] panels = {profilesPanel, createPanel, generatedPanel};
        TextView[] tabs = {tabProfiles, tabCreate, tabGenerated};
        for (int index = 0; index < panels.length; index++) {
            boolean selected = panels[index] == selectedPanel;
            panels[index].setVisibility(selected ? View.VISIBLE : View.GONE);
            tabs[index].setSelected(selected);
            tabs[index].setBackgroundResource(selected ? R.drawable.bg_tab_selected : 0);
            tabs[index].setTextColor(ContextCompat.getColor(this, selected ? R.color.surface : R.color.ink));
        }
        if (selectedPanel != createPanel) {
            textInput.clearFocus();
            WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView()).hide(WindowInsetsCompat.Type.ime());
        }
        if (selectedPanel == generatedPanel) refreshGenerations();
    }

    private void showGenerationText(GenerationRecord record) {
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(R.string.generation_text)
                .setMessage(record.text)
                .setPositiveButton(R.string.copy_text, (window, which) -> {
                    ClipboardManager clipboard = getSystemService(ClipboardManager.class);
                    clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.generation_text), record.text));
                })
                .setNegativeButton(R.string.close, null).create();
        dialog.show();
        TextView message = dialog.findViewById(android.R.id.message);
        if (message != null) message.setTextIsSelectable(true);
    }

    private void play(GenerationRecord record) {
        playAudio(record.generationId, generationStore.audioFile(record.generationId));
    }

    private void playAudio(String audioId, File audio) {
        if (recordingPending) { message("Stop recording before playing audio."); return; }
        try {
            if (player != null && audioId.equals(playingAudioId)) {
                if (player.isPlaying()) player.pause(); else player.start();
            } else {
                if (!audio.isFile()) { message("Audio file is missing"); return; }
                stopPlayback(); player = new MediaPlayer(); playingAudioId = audioId;
                player.setDataSource(audio.getAbsolutePath());
                player.setOnCompletionListener(mp -> { if (player == mp) stopPlayback(); });
                player.setOnErrorListener((mp, what, extra) -> {
                    if (player == mp) { stopPlayback(); message("Could not play audio."); }
                    return true;
                });
                player.prepare(); player.start();
            }
            updatePlaybackControls();
        } catch (Exception e) { stopPlayback(); message("Could not play audio: " + e.getMessage()); }
    }

    private void stopPlayback() {
        if (player != null) { player.release(); player = null; }
        playingAudioId = null; updatePlaybackControls();
    }

    private void updatePlaybackControls() {
        boolean playing = player != null && player.isPlaying();
        if (adapter != null) adapter.setPlayback(playingAudioId, playing);
        boolean samplePlaying = playing && profile != null && ("profile:" + profile.profileId).equals(playingAudioId);
        playSampleButton.setText(samplePlaying ? R.string.pause_sample : R.string.play_sample);
        playSampleButton.setIconResource(samplePlaying ? R.drawable.ic_pause : R.drawable.ic_play);
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS);
    }

    @Override protected void onNewIntent(Intent intent) { super.onNewIntent(intent); setIntent(intent); if (intent.getBooleanExtra("open_generated", false)) showPanel(generatedPanel); }
    @Override protected void onStart() { super.onStart(); handler.post(progressTicker); }
    @Override protected void onStop() { handler.removeCallbacks(progressTicker); super.onStop(); }
    @Override protected void onDestroy() {
        if (recordButton.getIcon() instanceof Animatable animation) animation.stop();
        handler.removeCallbacks(recordingTimeout); recorder.release();
        if (audioActions != null) audioActions.close();
        stopPlayback(); super.onDestroy();
    }
    private void message(String text) { Snackbar.make(findViewById(android.R.id.content), text, Snackbar.LENGTH_LONG).show(); }
    private void fatal(String text) { Toast.makeText(this, text, Toast.LENGTH_LONG).show(); generateButton.setEnabled(false); recordButton.setEnabled(false); }
}
