package com.example.yapit.provider;

import java.util.Arrays;

/** Prepares disposable inference inputs while preserving the original profile recording. */
final class PocketAudioPreparation {
    private PocketAudioPreparation() {}

    static float[] reference(float[] samples, int sampleRate, double maxSeconds) {
        if (sampleRate <= 0 || !Double.isFinite(maxSeconds) || maxSeconds <= 0 || maxSeconds > 30) {
            throw new IllegalArgumentException("Invalid reference sample rate or duration limit");
        }
        int length = Math.min(samples.length, (int) (sampleRate * maxSeconds));
        if (length == 0) throw new IllegalArgumentException("The selected voice sample contains no audio");
        int frame = Math.max(1, sampleRate / 50);
        double[] energy = new double[length / frame];
        double maximum = 0;
        for (int i = 0; i < length; i++) {
            if (!Float.isFinite(samples[i])) throw new IllegalArgumentException("Voice sample contains invalid audio");
            if (i / frame < energy.length) {
                energy[i / frame] += (double) samples[i] * samples[i] / frame;
                maximum = Math.max(maximum, energy[i / frame]);
            }
        }
        if (maximum == 0) throw new IllegalArgumentException("The selected voice sample contains only silence");
        if (energy.length == 0) return Arrays.copyOf(samples, length);
        int last = energy.length - 1;
        double threshold = maximum * Math.pow(10, -3.5);
        while (last > 0 && energy[last] <= threshold) last--;
        int end = (last + 1) * frame;
        float[] prepared = Arrays.copyOf(samples, end + sampleRate * 80 / 1000);
        Arrays.fill(prepared, end, prepared.length, 0);
        int fade = Math.min(sampleRate / 50, end);
        for (int i = 0; i < fade; i++) {
            prepared[end - fade + i] *= fade <= 1 ? 0 : 1f - (float) i / (fade - 1);
        }
        return prepared;
    }

    static String text(String input) {
        String text = input.replace('\u2018', '\'').replace('\u2019', '\'')
                .replaceAll("[\\s\\p{Z}]+", " ").trim();
        if (text.isEmpty()) throw new IllegalArgumentException("Enter text to generate");
        text = Character.toUpperCase(text.charAt(0)) + text.substring(1);
        char last = text.charAt(text.length() - 1);
        return ".!?\u2026".indexOf(last) >= 0 ? text : text + ".";
    }

    static void fadeOnset(float[] samples, int sampleRate) {
        int length = Math.min(samples.length, sampleRate / 200);
        for (int i = 0; i < length; i++) samples[i] *= length <= 1 ? 0 : (float) i / (length - 1);
    }
}
