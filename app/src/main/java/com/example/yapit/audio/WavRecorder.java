package com.example.yapit.audio;

import android.Manifest;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import androidx.annotation.RequiresPermission;
import java.io.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Records mono 16 kHz PCM and writes a standards-compliant 16-bit WAV sample. */
public final class WavRecorder {
    public interface Listener { void onStopped(File file, long durationMs); void onError(Exception error); }
    private static final int SAMPLE_RATE = 16000;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private volatile boolean recording;
    private AudioRecord recorder;
    private long startedAt;

    public synchronized boolean isRecording() { return recording; }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    public synchronized void start(File output, Listener listener) {
        if (recording) return;
        int min = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        int bufferSize = Math.max(min, 4096);
        recorder = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize * 2);
        if (recorder.getState() != AudioRecord.STATE_INITIALIZED) { recorder.release(); recorder = null; listener.onError(new IllegalStateException("Microphone could not be initialized")); return; }
        File parent = output.getParentFile(); if (parent != null) parent.mkdirs();
        recording = true; startedAt = System.currentTimeMillis(); recorder.startRecording();
        executor.execute(() -> recordLoop(output, bufferSize, listener));
    }

    public synchronized void stop() {
        if (!recording) return; recording = false;
        try { recorder.stop(); } catch (Exception ignored) { }
    }

    public synchronized void release() { stop(); executor.shutdown(); }

    private void recordLoop(File output, int bufferSize, Listener listener) {
        Exception failure = null;
        try (RandomAccessFile wav = new RandomAccessFile(output, "rw")) {
            wav.setLength(0); wav.write(new byte[44]); byte[] buffer = new byte[bufferSize]; long pcmBytes = 0;
            while (recording) {
                int count = recorder.read(buffer, 0, buffer.length);
                if (!recording) break;
                if (count > 0) { wav.write(buffer, 0, count); pcmBytes += count; }
                else if (count < 0) throw new IOException("AudioRecord read failed: " + count);
            }
            writeHeader(wav, pcmBytes);
        } catch (Exception e) { failure = e; }
        finally {
            synchronized (this) { if (recorder != null) { recorder.release(); recorder = null; } recording = false; }
        }
        if (failure != null) listener.onError(failure); else listener.onStopped(output, System.currentTimeMillis() - startedAt);
    }

    private static void writeHeader(RandomAccessFile out, long dataSize) throws IOException {
        long byteRate = SAMPLE_RATE * 2L; out.seek(0);
        out.writeBytes("RIFF"); writeLe32(out, dataSize + 36); out.writeBytes("WAVEfmt "); writeLe32(out, 16);
        writeLe16(out, 1); writeLe16(out, 1); writeLe32(out, SAMPLE_RATE); writeLe32(out, byteRate);
        writeLe16(out, 2); writeLe16(out, 16); out.writeBytes("data"); writeLe32(out, dataSize);
    }
    private static void writeLe16(RandomAccessFile out, long v) throws IOException { out.write((int)v & 0xff); out.write((int)(v >> 8) & 0xff); }
    private static void writeLe32(RandomAccessFile out, long v) throws IOException { for (int i = 0; i < 4; i++) out.write((int)(v >> (8 * i)) & 0xff); }
}
