package pl.trikiemu.app;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Odtwarzanie ruchu z pliku CSV - port tools/pc_motion_file.py.
 *  - 7 kolumn: t,gx,gy,gz,ax,ay,az = keyframe'y (interpolacja liniowa, probkowane 100 Hz),
 *  - 6 kolumn: gx,gy,gz,ax,ay,az = surowe probki grane z 50 Hz (domyslny --rate narzedzia).
 * '#' zaczyna komentarz. Po ostatniej probce: petla (loop) albo przytrzymanie ostatniej wartosci.
 */
final class CsvMotion implements MotionSource {
    private static final double FRAME_RATE = 1000.0 / Protocol.FRAME_PERIOD_MS; // 100 Hz
    private static final double RAW_RATE = 50.0;

    private final float[][] samples;      // kazda probka: 6 floatow, jedna na ramke (100 Hz)
    private volatile boolean loop = true;
    private int idx = 0;

    private CsvMotion(float[][] samples) {
        this.samples = samples;
    }

    int sampleCount() {
        return samples.length;
    }

    double durationSeconds() {
        return samples.length / FRAME_RATE;
    }

    void setLoop(boolean loop) {
        this.loop = loop;
    }

    void rewind() {
        synchronized (this) { idx = 0; }
    }

    @Override
    public synchronized void next(float[] out) {
        float[] s = samples[idx];
        System.arraycopy(s, 0, out, 0, 6);
        if (idx + 1 < samples.length) {
            idx++;
        } else if (loop) {
            idx = 0;
        }
    }

    // ---- parsowanie ----

    static CsvMotion parse(InputStream in) throws IOException {
        List<double[]> rows = new ArrayList<>();
        BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        String raw;
        int lineNo = 0;
        int ncol = -1;
        long total = 0;
        while ((raw = br.readLine()) != null) {
            lineNo++;
            total += raw.length() + 1;
            if (total > 8L * 1024 * 1024) throw new IllegalArgumentException("Plik zbyt duzy (max 8 MB)");
            if (lineNo == 1 && raw.startsWith("﻿")) raw = raw.substring(1);
            int h = raw.indexOf('#');
            String line = (h >= 0 ? raw.substring(0, h) : raw).trim();
            if (line.isEmpty()) continue;
            String[] parts = line.split(",");
            double[] row = new double[parts.length];
            for (int i = 0; i < parts.length; i++) {
                try {
                    row[i] = Double.parseDouble(parts[i].trim());
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("Linia " + lineNo + ": nie liczba: '" + parts[i].trim() + "'");
                }
                if (Double.isNaN(row[i]) || Double.isInfinite(row[i])) {
                    throw new IllegalArgumentException("Linia " + lineNo + ": niedozwolona wartosc");
                }
            }
            if (ncol < 0) {
                ncol = row.length;
                if (ncol != 6 && ncol != 7) {
                    throw new IllegalArgumentException("Oczekiwano 6 lub 7 kolumn, jest " + ncol);
                }
            } else if (row.length != ncol) {
                throw new IllegalArgumentException("Linia " + lineNo + ": " + row.length + " kolumn zamiast " + ncol);
            }
            rows.add(row);
        }
        if (rows.isEmpty()) throw new IllegalArgumentException("Plik nie zawiera danych");
        return new CsvMotion(ncol == 7 ? fromKeyframes(rows) : fromRaw(rows));
    }

    private static float[][] fromRaw(List<double[]> rows) {
        // probka z pliku trzymana przez FRAME_RATE / RAW_RATE ramek (100/50 = 2)
        int hold = (int) Math.max(1, Math.rint(FRAME_RATE / RAW_RATE));
        float[][] out = new float[rows.size() * hold][6];
        int k = 0;
        for (double[] r : rows) {
            for (int h = 0; h < hold; h++) {
                for (int c = 0; c < 6; c++) out[k][c] = (float) r[c];
                k++;
            }
        }
        return out;
    }

    private static float[][] fromKeyframes(List<double[]> rowsIn) {
        double[][] rows = rowsIn.toArray(new double[0][]);
        Arrays.sort(rows, new Comparator<double[]>() {
            @Override public int compare(double[] a, double[] b) { return Double.compare(a[0], b[0]); }
        });
        double tEnd = rows[rows.length - 1][0];
        int n = (int) Math.max(1, Math.rint(tEnd * FRAME_RATE));
        if (n > 360000) throw new IllegalArgumentException("Sekwencja zbyt dluga (max 1 h)");
        double dt = 1.0 / FRAME_RATE;
        float[][] out = new float[n + 1][6];
        int j = 0;
        for (int i = 0; i <= n; i++) {
            double t = i * dt;
            while (j + 1 < rows.length && rows[j + 1][0] <= t) j++;
            double[] a = rows[j];
            double[] b = rows[Math.min(j + 1, rows.length - 1)];
            double span = b[0] - a[0];
            double f = span <= 0 ? 0.0 : Math.max(0.0, Math.min(1.0, (t - a[0]) / span));
            for (int k = 0; k < 6; k++) {
                out[i][k] = (float) (a[1 + k] + (b[1 + k] - a[1 + k]) * f);
            }
        }
        return out;
    }
}
