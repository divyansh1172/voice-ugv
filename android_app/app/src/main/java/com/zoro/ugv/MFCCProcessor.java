package com.zoro.ugv;

import java.util.Arrays;

/**
 * MFCCProcessor — direct Java port of esp32_side/src/mfcc.cpp
 *
 * Produces 39-coefficient frames (13 raw + 13 delta + 13 delta-delta) with CMVN normalisation.
 * Parameters exactly match the ESP32 implementation so template scores are comparable.
 *
 * Enhanced version:
 *   - Voice Activity Detection (VAD) to trim silence.
 *   - Delta-Delta (acceleration) coefficients for better dynamics.
 *   - Optimized DCT and memory reuse.
 */
public class MFCCProcessor {

    // Must match mfcc.h constants
    public static final int SAMPLE_RATE  = 8000;
    public static final int FRAME_SIZE   = 256;
    public static final int HOP_SIZE     = 128;
    public static final int NUM_FILTERS  = 26;
    public static final int NUM_RAW      = 13;
    public static final int NUM_COEFFS   = 39;   // 13 raw + 13 delta + 13 delta-delta
    public static final int MAX_FRAMES   = 100;
    public static final int NUM_SAMPLES  = 12000; // 1.5 s @ 8 kHz

    private final float[]  hammingWin;
    private final int[]    filterBins;
    private final float[][] dctMatrix;

    // Reusable buffers
    private final double[] fftReal     = new double[FRAME_SIZE];
    private final double[] fftImag     = new double[FRAME_SIZE];
    private final float[]  powSpec      = new float[FRAME_SIZE / 2 + 1];
    private final float[]  melEnergies  = new float[NUM_FILTERS];

    public MFCCProcessor() {
        // Pre-compute Hamming window
        hammingWin = new float[FRAME_SIZE];
        for (int i = 0; i < FRAME_SIZE; i++) {
            hammingWin[i] = 0.54f - 0.46f * (float) Math.cos(
                    2.0 * Math.PI * i / (FRAME_SIZE - 1));
        }

        // Pre-compute Mel filterbank bin boundaries
        filterBins = new int[NUM_FILTERS + 2];
        float highHz  = SAMPLE_RATE / 2.0f;
        float highMel = 2595.0f * (float) Math.log10(1.0f + highHz / 700.0f);
        for (int i = 0; i <= NUM_FILTERS + 1; i++) {
            float mel = i * highMel / (NUM_FILTERS + 1);
            float hz  = 700.0f * ((float) Math.pow(10.0f, mel / 2595.0f) - 1.0f);
            filterBins[i] = (int) Math.floor((FRAME_SIZE + 1) * hz / SAMPLE_RATE);
        }

        // Pre-compute DCT matrix (NUM_RAW x NUM_FILTERS)
        dctMatrix = new float[NUM_RAW][NUM_FILTERS];
        float s0 = (float) Math.sqrt(1.0 / NUM_FILTERS);
        float s1 = (float) Math.sqrt(2.0 / NUM_FILTERS);
        for (int k = 0; k < NUM_RAW; k++) {
            for (int n = 0; n < NUM_FILTERS; n++) {
                float val = (float) Math.cos(Math.PI * k * (2 * n + 1) / (2.0 * NUM_FILTERS));
                dctMatrix[k][n] = val * (k == 0 ? s0 : s1);
            }
        }
    }

    /**
     * Extract MFCC features from raw 16-bit PCM audio.
     */
    public synchronized float[][] extract(short[] audio) {
        // ── Voice Activity Detection (VAD) ───────────────────────────────────
        int startSample = 0;
        int endSample   = audio.length;
        float energyThresh = 0.0005f; 

        for (int i = 0; i < audio.length; i += HOP_SIZE) {
            float energy = 0;
            int limit = Math.min(i + FRAME_SIZE, audio.length);
            for (int k = i; k < limit; k++) {
                float v = audio[k] / 32768.0f;
                energy += v * v;
            }
            energy /= FRAME_SIZE;
            if (energy > energyThresh) {
                startSample = Math.max(0, i - FRAME_SIZE);
                break;
            }
        }
        for (int i = audio.length - FRAME_SIZE; i > startSample; i -= HOP_SIZE) {
            float energy = 0;
            for (int k = i; k < i + FRAME_SIZE; k++) {
                float v = audio[k] / 32768.0f;
                energy += v * v;
            }
            energy /= FRAME_SIZE;
            if (energy > energyThresh) {
                endSample = Math.min(audio.length, i + FRAME_SIZE);
                break;
            }
        }

        int numSamples = Math.min(endSample - startSample, NUM_SAMPLES);
        if (numSamples < FRAME_SIZE) return new float[0][NUM_COEFFS];

        int numFrames  = (numSamples - FRAME_SIZE) / HOP_SIZE + 1;
        if (numFrames > MAX_FRAMES) numFrames = MAX_FRAMES;

        final int HALF = FRAME_SIZE / 2 + 1;
        float[][] mfccOut = new float[numFrames][NUM_COEFFS];

        // ── Pass 1: 13 raw MFCC coefficients ─────────────────────────────────
        for (int f = 0; f < numFrames; f++) {
            int start = startSample + (f * HOP_SIZE);

            float prev = audio[start] / 32768.0f;
            fftReal[0] = prev * hammingWin[0];
            fftImag[0] = 0.0;
            for (int i = 1; i < FRAME_SIZE; i++) {
                float curr  = audio[start + i] / 32768.0f;
                float emph  = curr - 0.97f * prev;
                fftReal[i]  = emph * hammingWin[i];
                fftImag[i]  = 0.0;
                prev = curr;
            }

            fft(fftReal, fftImag);

            for (int i = 0; i < HALF; i++) {
                float re = (float) fftReal[i];
                float im = (float) fftImag[i];
                powSpec[i] = (re * re + im * im) / FRAME_SIZE;
            }

            for (int m = 0; m < NUM_FILTERS; m++) {
                int   fl     = filterBins[m];
                int   fc     = filterBins[m + 1];
                int   fr     = filterBins[m + 2];
                float energy = 0.0f;
                if (fc > fl) {
                    for (int k = fl; k < fc && k < HALF; k++)
                        energy += powSpec[k] * (float)(k - fl) / (fc - fl);
                }
                if (fr > fc) {
                    for (int k = fc; k < fr && k < HALF; k++)
                        energy += powSpec[k] * (float)(fr - k) / (fr - fc);
                }
                melEnergies[m] = (energy > 1e-10f) ? (float) Math.log(energy) : -23.0f;
            }

            for (int k = 0; k < NUM_RAW; k++) {
                float sum = 0.0f;
                for (int n = 0; n < NUM_FILTERS; n++) {
                    sum += melEnergies[n] * dctMatrix[k][n];
                }
                mfccOut[f][k] = sum;
            }
        }

        // ── Pass 2: Delta coefficients ───────────────────────────────────────
        for (int f = 0; f < numFrames; f++) {
            int fp = (f > 0)              ? f - 1 : 0;
            int fn = (f < numFrames - 1)  ? f + 1 : numFrames - 1;
            for (int c = 0; c < NUM_RAW; c++) {
                mfccOut[f][NUM_RAW + c] = (mfccOut[fn][c] - mfccOut[fp][c]) / 2.0f;
            }
        }

        // ── Pass 3: Delta-Delta coefficients ─────────────────────────────────
        for (int f = 0; f < numFrames; f++) {
            int fp = (f > 0)              ? f - 1 : 0;
            int fn = (f < numFrames - 1)  ? f + 1 : numFrames - 1;
            for (int c = 0; c < NUM_RAW; c++) {
                mfccOut[f][2 * NUM_RAW + c] = (mfccOut[fn][NUM_RAW + c] - mfccOut[fp][NUM_RAW + c]) / 2.0f;
            }
        }

        // ── Pass 4: CMVN ─────────────────────────────────────────────────────
        for (int c = 0; c < NUM_COEFFS; c++) {
            float mean = 0.0f;
            for (int f = 0; f < numFrames; f++) mean += mfccOut[f][c];
            mean /= numFrames;

            float var = 0.0f;
            for (int f = 0; f < numFrames; f++) {
                float d = mfccOut[f][c] - mean;
                var += d * d;
            }
            float stdInv = 1.0f / ((float) Math.sqrt(var / numFrames) + 1e-8f);
            for (int f = 0; f < numFrames; f++) {
                mfccOut[f][c] = (mfccOut[f][c] - mean) * stdInv;
            }
        }

        return Arrays.copyOf(mfccOut, numFrames);
    }

    private void fft(double[] re, double[] im) {
        int n = re.length;
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) {
                double t; t = re[i]; re[i] = re[j]; re[j] = t;
                           t = im[i]; im[i] = im[j]; im[j] = t;
            }
        }
        for (int len = 2; len <= n; len <<= 1) {
            double ang  = -2.0 * Math.PI / len;
            double wRe  = Math.cos(ang);
            double wIm  = Math.sin(ang);
            for (int i = 0; i < n; i += len) {
                double curRe = 1.0, curIm = 0.0;
                for (int j = 0; j < len / 2; j++) {
                    double uRe = re[i + j],            uIm = im[i + j];
                    double vRe = re[i + j + len/2] * curRe - im[i + j + len/2] * curIm;
                    double vIm = re[i + j + len/2] * curIm + im[i + j + len/2] * curRe;
                    re[i + j]          = uRe + vRe;  im[i + j]          = uIm + vIm;
                    re[i + j + len/2]  = uRe - vRe;  im[i + j + len/2]  = uIm - vIm;
                    double newRe = curRe * wRe - curIm * wIm;
                    curIm = curRe * wIm + curIm * wRe;
                    curRe = newRe;
                }
            }
        }
    }
}
