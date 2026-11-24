package com.example.yapit.store;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;
import com.example.yapit.model.GenerationRecord;
import java.io.*;
import java.nio.file.Files;

/** Publishes generated WAV files to the app-owned Downloads/YapIt collection for browsing and sharing. */
public final class GeneratedAudioFiles {
    public static final String RELATIVE_DIRECTORY = Environment.DIRECTORY_DOWNLOADS + "/YapIt/";
    private final ContentResolver resolver;
    private final GenerationStore generations;
    public GeneratedAudioFiles(Context context) {
        resolver = context.getContentResolver(); generations = new GenerationStore(context);
    }

    public synchronized Uri publish(GenerationRecord record) throws IOException {
        String name = "YapIt-" + record.generationId + ".wav";
        Uri collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI;
        Uri existing = find(record);
        if (existing != null) return existing;
        File audio = generations.audioFile(record.generationId);
        if (!audio.isFile()) throw new FileNotFoundException("Generated audio is missing");
        ContentValues values = new ContentValues();
        values.put(MediaStore.Downloads.DISPLAY_NAME, name); values.put(MediaStore.Downloads.MIME_TYPE, "audio/wav");
        values.put(MediaStore.Downloads.RELATIVE_PATH, RELATIVE_DIRECTORY); values.put(MediaStore.Downloads.IS_PENDING, 1);
        Uri uri = resolver.insert(collection, values);
        if (uri == null) throw new IOException("Could not create the Downloads audio file");
        try {
            try (OutputStream output = resolver.openOutputStream(uri)) {
                if (output == null) throw new IOException("Could not open the Downloads audio file");
                Files.copy(audio.toPath(), output);
            }
            values.clear(); values.put(MediaStore.Downloads.IS_PENDING, 0); resolver.update(uri, values, null, null);
            return uri;
        } catch (IOException | RuntimeException error) {
            resolver.delete(uri, null, null);
            throw error;
        }
    }

    public synchronized void delete(GenerationRecord record) {
        Uri uri = find(record);
        if (uri != null) resolver.delete(uri, null, null);
    }

    private Uri find(GenerationRecord record) {
        try (Cursor cursor = resolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI, new String[]{MediaStore.Downloads._ID},
                MediaStore.Downloads.RELATIVE_PATH + "=? AND " + MediaStore.Downloads.DISPLAY_NAME + "=?",
                new String[]{RELATIVE_DIRECTORY, "YapIt-" + record.generationId + ".wav"}, null)) {
            if (cursor != null && cursor.moveToFirst()) return ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cursor.getLong(0));
        }
        return null;
    }
}
