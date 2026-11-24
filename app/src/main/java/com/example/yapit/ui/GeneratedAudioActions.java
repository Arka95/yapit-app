package com.example.yapit.ui;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.Environment;
import android.provider.DocumentsContract;
import com.example.yapit.model.GenerationRecord;
import com.example.yapit.store.GeneratedAudioFiles;
import com.example.yapit.store.GenerationStore;
import com.google.android.material.snackbar.Snackbar;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** Exports audio off the UI thread and opens Android file browsing or the share sheet. */
final class GeneratedAudioActions implements AutoCloseable {
    private final Activity activity;
    private final GeneratedAudioFiles files;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    GeneratedAudioActions(Activity activity) { this.activity = activity; files = new GeneratedAudioFiles(activity.getApplicationContext()); }

    void share(GenerationRecord record) {
        export(record, uri -> {
            Intent intent = new Intent(Intent.ACTION_SEND).setType("audio/wav").putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.setClipData(ClipData.newRawUri("Generated audio", uri));
            activity.startActivity(Intent.createChooser(intent, "Share generated audio"));
        });
    }

    void openFolder(GenerationRecord record) {
        export(record, uri -> {
            Uri directory = DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:" + Environment.DIRECTORY_DOWNLOADS + "/YapIt");
            try {
                activity.startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(directory, DocumentsContract.Document.MIME_TYPE_DIR));
            } catch (ActivityNotFoundException unavailable) {
                activity.startActivity(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).putExtra(DocumentsContract.EXTRA_INITIAL_URI, directory));
            }

        });
    }

    void delete(GenerationRecord record, Runnable onDeleted) {
        executor.execute(() -> {
            try {
                files.delete(record);
                new GenerationStore(activity.getApplicationContext()).delete(record.generationId);
                activity.runOnUiThread(() -> { if (!activity.isDestroyed()) onDeleted.run(); });
            } catch (Exception error) { activity.runOnUiThread(() -> message("Could not delete the generated audio.")); }
        });
    }

    private void export(GenerationRecord record, Consumer<Uri> action) {
        executor.execute(() -> {
            try {
                Uri uri = files.publish(record);
                activity.runOnUiThread(() -> {
                    if (activity.isDestroyed()) return;
                    try { action.accept(uri); } catch (Exception error) { message("No compatible app could open this audio action."); }
                });
            } catch (Exception error) { activity.runOnUiThread(() -> message("Could not export audio to Downloads/YapIt.")); }
        });
    }
    private void message(String text) {
        if (!activity.isDestroyed()) Snackbar.make(activity.findViewById(android.R.id.content), text, Snackbar.LENGTH_LONG).show();
    }
    @Override public void close() { executor.shutdown(); }
}
