/*
 * Copyright (C) 2022 By yours truly, Daniel Jacob Chittoor
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package co.aospa.glyph.services;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.media.audiofx.Visualizer;
import android.os.IBinder;
import android.util.Log;

import java.util.Arrays;

import co.aospa.glyph.manager.AnimationManager;
import co.aospa.glyph.utils.Constants;

public class MusicVisualizerService extends Service {

    private static final String TAG = "GlyphMusicVisualizerService";
    private static final boolean DEBUG = true;

    private static final float DECAY_FACTOR = 0.78f;

    // placeholder index slices for Phone 2's 33-element frame
    // TODO adjust to match tested LEDs
    private static final int[] PHONE2_LOW_INDICES = {0, 1, 2, 3, 4, 5, 6, 7};
    private static final int[] PHONE2_MID_LOW_INDICES = {8, 9, 10, 11, 12, 13};
    private static final int[] PHONE2_MID_INDICES = {14, 15, 16, 17, 18, 19};
    private static final int[] PHONE2_MID_HIGH_INDICES = {20, 21, 22, 23, 24, 25};
    private static final int[] PHONE2_HIGH_INDICES = {26, 27, 28, 29, 30, 31, 32};

    private Visualizer mVisualizer;
    private int bufferSize;
    //private boolean isRecording = false;

    private double mRunningSoundAvg[];             // Total sound energy in one second  (0=low, 1=mid low, 2=mid, 3=mid high, 4=high)
    private double mCurrentAvgEnergyOneSec[];      // Average sound energy in one second (0=low, 1=mid low, 2=mid, 3=mid high, 4=high)
    private int mNumberOfSamplesInOneSec;          // Number of samples in one second
    private long mSystemTimeStartSec;              // System time at the start of a one second interval

    private float[] mFrameBrightness;			   // Decaying value for each LED slot
    private float[] mBandTargets;				   // Target brightness calculated for each frequency, before LED mapping and decay

    // Define the max value for a frequency band
    private static final int LOW_FREQUENCY = 200;
    private static final int MID_LOW_FREQUENCY = 500;
    private static final int MID_FREQUENCY = 1500;
    private static final int MID_HIGH_FREQUENCY = 5000;
    private static final int HIGH_FREQUENCY = 10000;

    @Override
    public void onCreate() {
        if (DEBUG) Log.d(TAG, "Creating service");

        AudioManager mAudioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);

        // Create a visualizer with the audio session ID (0) which takes the entire output mix
        mVisualizer = new Visualizer(0);

        // Set the capture size to the maximum available
        bufferSize = Visualizer.getCaptureSizeRange()[1];
        mVisualizer.setCaptureSize(bufferSize);

        int frameSize = "phone2".equals(Constants.getDevice()) ? 33 : 5;
                mFrameBrightness = new float[frameSize];
                mBandTargets = new float[5];

        mVisualizer.setDataCaptureListener(
            new Visualizer.OnDataCaptureListener() {
                @Override
                public void onWaveFormDataCapture(
                        Visualizer visualizer, byte[] waveform, int samplingRate) {
                }

                @Override
                public void onFftDataCapture(
                        Visualizer visualizer, byte[] fft, int samplingRate) {
                    if (mAudioManager.isMusicActive()) {
                        if (DEBUG) Log.d(TAG, "Music is active");
                        processAudioFFT(fft);
                    } else {
                        fadeOutFrame();
                    }
                }
            }, Visualizer.getMaxCaptureRate() / 2, false, true
        );

        mVisualizer.setEnabled(true);

        // Initialize instance variables
        mRunningSoundAvg = new double[5];
        mCurrentAvgEnergyOneSec = new double[5];
        mCurrentAvgEnergyOneSec[0] = -1;
        mCurrentAvgEnergyOneSec[1] = -1;
        mCurrentAvgEnergyOneSec[2] = -1;
        mCurrentAvgEnergyOneSec[3] = -1;
        mCurrentAvgEnergyOneSec[4] = -1;

        // Set the start time for the current one second interval
        mSystemTimeStartSec = System.currentTimeMillis();

    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (DEBUG) Log.d(TAG, "Starting service");
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (DEBUG) Log.d(TAG, "Destroying service");
        mVisualizer.setEnabled(false);
        mVisualizer.release();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /* original function
    private void processAudioFFT(byte[] audioBytes) {
        // The first byte is the DC component of the FFT result (real only)
        int energySum = Math.abs(audioBytes[0]);

        // Calculate the average instantaneous energy of the low frequency band
        int k = 2;
        double captureSize = mVisualizer.getCaptureSize() / 2.0;
        int sampleRate = mVisualizer.getSamplingRate() / 2000;
        double nextFrequency = (k / 2.0 * sampleRate) / captureSize;

        // Sum the energy in the low frequency band
        while (nextFrequency < LOW_FREQUENCY) {
            // Calculate the energy of the current frequency
            energySum += Math.sqrt(audioBytes[k] * audioBytes[k] + audioBytes[k + 1] * audioBytes[k + 1]);

            // Increment the frequency index
            k += 2;
            nextFrequency = (k / 2.0 * sampleRate) / captureSize;
        }

        // Calculate the average energy in the low frequency band
        double sampleAvgAudioEnergy = energySum / (k / 2.0);

        // Accumulate the low frequency band energy over time
        mRunningSoundAvg[0] += sampleAvgAudioEnergy;

        // Check for a beat in the low frequency band
        // A beat occurs when the average sound energy of a sample is greater than
        // the average sound energy of a one second part of a song
        // Also make sure the mCurrentAvgEnergy has been set, otherwise its -1 before its first pass
        if( (sampleAvgAudioEnergy >  mCurrentAvgEnergyOneSec[0]) && (mCurrentAvgEnergyOneSec[0] > 0) ) {
            if (DEBUG) Log.d(TAG, "Low frequency band beat detected");
            AnimationManager.playMusic("low");
        }

        energySum = 0;

        // Sum the energy in the mid-low frequency band
        while (nextFrequency < MID_LOW_FREQUENCY) {
            // Calculate the energy of the current frequency
            energySum += Math.sqrt(audioBytes[k] * audioBytes[k] + audioBytes[k + 1] * audioBytes[k + 1]);

            // Increment the frequency index
            k += 2;
            nextFrequency = (k / 2.0 * sampleRate) / captureSize;
        }

        // Calculate the average energy in the mid-low frequency band
        sampleAvgAudioEnergy = energySum / (k / 2.0);

        // Accumulate the mid low frequency band energy over time
        mRunningSoundAvg[1] += sampleAvgAudioEnergy;

        // Check for a beat in the mid-low frequency band
        if((sampleAvgAudioEnergy >  mCurrentAvgEnergyOneSec[1]) && (mCurrentAvgEnergyOneSec[1] > 0)) {
            if (DEBUG) Log.d(TAG, "Mid-low frequency band beat detected");
            AnimationManager.playMusic("mid_low");
        }

        energySum = 0;

        // Sum the energy in the mid frequency band
        while (nextFrequency < MID_FREQUENCY) {
            // Calculate the energy of the current frequency
            energySum += Math.sqrt(audioBytes[k] * audioBytes[k] + audioBytes[k + 1] * audioBytes[k + 1]);

            // Increment the frequency index
            k += 2;
            nextFrequency = (k / 2.0 * sampleRate) / captureSize;
        }

        // Calculate the average energy in the mid frequency band
        sampleAvgAudioEnergy = energySum / (k / 2.0);

        // Accumulate the mid frequency band energy over time
        mRunningSoundAvg[2] += sampleAvgAudioEnergy;

        // Check for a beat in the mid frequency band
        if( (sampleAvgAudioEnergy >  mCurrentAvgEnergyOneSec[2]) && (mCurrentAvgEnergyOneSec[2] > 0) ) {
            if (DEBUG) Log.d(TAG, "Mid frequency band beat detected");
            AnimationManager.playMusic("mid");
        }

        energySum = 0;

        // Sum the energy in the mid-high frequency band
        while (nextFrequency < MID_HIGH_FREQUENCY) {
            // Calculate the energy of the current frequency
            energySum += Math.sqrt(audioBytes[k] * audioBytes[k] + audioBytes[k + 1] * audioBytes[k + 1]);

            // Increment the frequency index
            k += 2;
            nextFrequency = (k / 2.0 * sampleRate) / captureSize;
        }

        // Calculate the average energy in the mid-high frequency band
        sampleAvgAudioEnergy = energySum / (k / 2.0);

        // Accumulate the mid high-frequency band energy over time
        mRunningSoundAvg[3] += sampleAvgAudioEnergy;

        // Check for a beat in the mid-high frequency band
        if( (sampleAvgAudioEnergy >  mCurrentAvgEnergyOneSec[3]) && (mCurrentAvgEnergyOneSec[3] > 0) ) {
            if (DEBUG) Log.d(TAG, "Mid-high frequency band beat detected");
            AnimationManager.playMusic("mid_high");
        }

        // Second Byte: Only imaginary part of the last frequency (include in highs)
        energySum = Math.abs(audioBytes[1]);

        // Sum the energy in the high frequency band
        while (nextFrequency < HIGH_FREQUENCY) {
            // Calculate the energy of the current frequency
            energySum += Math.sqrt(audioBytes[k] * audioBytes[k] + audioBytes[k + 1] * audioBytes[k + 1]);

            // Increment the frequency index
            k += 2;
            nextFrequency = (k / 2.0 * sampleRate) / captureSize;
        }

        // Calculate the average energy in the high frequency band
        sampleAvgAudioEnergy = energySum / (k / 2.0);

        // Accumulate the high frequency band energy over time
        mRunningSoundAvg[4] += sampleAvgAudioEnergy;

        // Check for a beat in the high frequency band
        if( (sampleAvgAudioEnergy >  mCurrentAvgEnergyOneSec[4]) && (mCurrentAvgEnergyOneSec[4] > 0) ) {
            if (DEBUG) Log.d(TAG, "High frequency band beat detected");
            AnimationManager.playMusic("high");
        }

        long currentTime = System.currentTimeMillis();
        if (currentTime - mSystemTimeStartSec >= 1000) {
            mCurrentAvgEnergyOneSec[0] = mRunningSoundAvg[0] / mNumberOfSamplesInOneSec;
            mCurrentAvgEnergyOneSec[1] = mRunningSoundAvg[1] / mNumberOfSamplesInOneSec;
            mCurrentAvgEnergyOneSec[2] = mRunningSoundAvg[2] / mNumberOfSamplesInOneSec;
            mCurrentAvgEnergyOneSec[3] = mRunningSoundAvg[3] / mNumberOfSamplesInOneSec;
            mCurrentAvgEnergyOneSec[4] = mRunningSoundAvg[4] / mNumberOfSamplesInOneSec;


            // Reset the running energy sum and sample count
            mRunningSoundAvg[0] = 0;
            mRunningSoundAvg[1] = 0;
            mRunningSoundAvg[2] = 0;
            mRunningSoundAvg[3] = 0;
            mRunningSoundAvg[4] = 0;
            mNumberOfSamplesInOneSec = 0;

            // Update the start time for the next one-second interval
            mSystemTimeStartSec = currentTime;
        }
        mNumberOfSamplesInOneSec++;

    }
     */

    // Compares current energy in one frequency band against its 1-second average
    // and returns a target brightness (0 up to max brightness) when there is a spike
    // basically, it makes small spikes in music dimmer and strong beats brighter.
    private float computeBandTarget(int bandIndex, double sampleAvgEnergy) {
        // Add this sample's energy to the running total for the current 1-second window
        mRunningSoundAvg[bandIndex] += sampleAvgEnergy;

        double avg = mCurrentAvgEnergyOneSec[bandIndex];
        // Skip until we have a valid avg
        if (avg <= 0.0 || sampleAvgEnergy <= avg) {
            return 0f;
        }

        float maxBrightness = (float) Constants.getMaxBrightness();
        // Measure how far above the 1-second average this band is, then scale to 0.0..1.0
        double ratio = (sampleAvgEnergy - avg) / avg;
        float scaled = (float) Math.min(1.0, ratio * 1.5);
        return scaled * maxBrightness;
    }

    // Updates LED slot indices using downward decay so lights fade smoothly
    private void applyIndices(int[] indices, float targetBrightness) {
        for (int idx : indices) {
            // Guard against out-of-range indices for the current device's frame array
            if (idx >= 0 && idx < mFrameBrightness.length) {
                // Multiply previous brightness by DECAY_FACTOR to fade down gradually
                float decayed = mFrameBrightness[idx] * DECAY_FACTOR;
                // update to targetBrightness if higher, otherwise keep the decayed value
                mFrameBrightness[idx] = Math.max(targetBrightness, decayed);
            }
        }
    }

    // send float frame to AnimationManager to update LEDs
    private void pushFrameToManager() {
            AnimationManager.updateLedFrame(Arrays.copyOf(mFrameBrightness, mFrameBrightness.length));
        }

    // Gradually fades all LEDs down to 0 when music stops playing
    private void fadeOutFrame() {
        boolean anyActive = false;
        for (int i = 0; i < mFrameBrightness.length; i++) {
            mFrameBrightness[i] *= DECAY_FACTOR;
            // Snap tiny leftover values to 0 so LEDs turn all the way off cleanly
            if (mFrameBrightness[i] < 1f) {
                mFrameBrightness[i] = 0f;
            } else {
                anyActive = true;
            }
        }
        // Only push a frame if at least one LED is still above 0
        if (anyActive) {
            pushFrameToManager();
        }
    }

    // Reads FFT frequency bins from audioBytes, splits them into 5 frequency bands,
    // updates the LED slots with decay, and refreshes the 1-second band averages
    private void processAudioFFT(byte[] audioBytes) {
        // First byte holds DC energy; k starts at 2 because FFT bins come in real/imaginary pairs
        int energySum = Math.abs(audioBytes[0]);
        int k = 2;
        double captureSize = mVisualizer.getCaptureSize() / 2.0;
        int sampleRate = mVisualizer.getSamplingRate() / 2000;
        double nextFrequency = (k / 2.0 * sampleRate) / captureSize;

        // 1. Low frequencies (up to LOW_FREQUENCY)
        while (nextFrequency < LOW_FREQUENCY && k + 1 < audioBytes.length) {
            energySum += Math.hypot(audioBytes[k], audioBytes[k + 1]);
            k += 2;
            nextFrequency = (k / 2.0 * sampleRate) / captureSize;
        }
        mBandTargets[0] = computeBandTarget(0, energySum / Math.max(1.0, k / 2.0));

        // 2. Mid-low frequencies (LOW_FREQUENCY up to MID_LOW_FREQUENCY)
        energySum = 0;
        int prevK = k;
        while (nextFrequency < MID_LOW_FREQUENCY && k + 1 < audioBytes.length) {
            energySum += Math.hypot(audioBytes[k], audioBytes[k + 1]);
            k += 2;
            nextFrequency = (k / 2.0 * sampleRate) / captureSize;
        }
        mBandTargets[1] = computeBandTarget(1, energySum / Math.max(1.0, (k - prevK) / 2.0));

        // 3. Mid frequencies (MID_LOW_FREQUENCY up to MID_FREQUENCY)
        energySum = 0;
        prevK = k;
        while (nextFrequency < MID_FREQUENCY && k + 1 < audioBytes.length) {
            energySum += Math.hypot(audioBytes[k], audioBytes[k + 1]);
            k += 2;
            nextFrequency = (k / 2.0 * sampleRate) / captureSize;
        }
        mBandTargets[2] = computeBandTarget(2, energySum / Math.max(1.0, (k - prevK) / 2.0));

        // 4. Mid-high frequencies (MID_FREQUENCY up to MID_HIGH_FREQUENCY)
        energySum = 0;
        prevK = k;
        while (nextFrequency < MID_HIGH_FREQUENCY && k + 1 < audioBytes.length) {
            energySum += Math.hypot(audioBytes[k], audioBytes[k + 1]);
            k += 2;
            nextFrequency = (k / 2.0 * sampleRate) / captureSize;
        }
        mBandTargets[3] = computeBandTarget(3, energySum / Math.max(1.0, (k - prevK) / 2.0));

        // 5. High frequencies (MID_HIGH_FREQUENCY up to HIGH_FREQUENCY); byte 1 is Nyquist imaginary part
        energySum = Math.abs(audioBytes[1]);
        prevK = k;
        while (nextFrequency < HIGH_FREQUENCY && k + 1 < audioBytes.length) {
            energySum += Math.hypot(audioBytes[k], audioBytes[k + 1]);
            k += 2;
            nextFrequency = (k / 2.0 * sampleRate) / captureSize;
        }
        mBandTargets[4] = computeBandTarget(4, energySum / Math.max(1.0, (k - prevK) / 2.0));

        // Map each band's target brightness onto its assigned LED slots
        if (mFrameBrightness.length == 33) {
            // Phone 2 uses a 33-slot frame split across the 5 frequency bands
            applyIndices(PHONE2_LOW_INDICES, mBandTargets[0]);
            applyIndices(PHONE2_MID_LOW_INDICES, mBandTargets[1]);
            applyIndices(PHONE2_MID_INDICES, mBandTargets[2]);
            applyIndices(PHONE2_MID_HIGH_INDICES, mBandTargets[3]);
            applyIndices(PHONE2_HIGH_INDICES, mBandTargets[4]);
        } else {
            // Fallback for non-33-slot layouts: apply decay directly per slot
            for (int i = 0; i < mFrameBrightness.length && i < mBandTargets.length; i++) {
                mFrameBrightness[i] = Math.max(mBandTargets[i], mFrameBrightness[i] * DECAY_FACTOR);
            }
        }

        // Send the updated frame to the LEDs
        pushFrameToManager();

        // Every 1000 ms, recalculate the average energy per band for the next second's beat comparison
        long currentTime = System.currentTimeMillis();
        if (currentTime - mSystemTimeStartSec >= 1000) {
            if (mNumberOfSamplesInOneSec > 0) {
                for (int i = 0; i < 5; i++) {
                    mCurrentAvgEnergyOneSec[i] = mRunningSoundAvg[i] / mNumberOfSamplesInOneSec;
                    mRunningSoundAvg[i] = 0;
                }
            }
            mNumberOfSamplesInOneSec = 0;
            mSystemTimeStartSec = currentTime;
        }
        mNumberOfSamplesInOneSec++;
    }
}
