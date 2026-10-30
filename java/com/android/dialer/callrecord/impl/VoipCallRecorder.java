//Vmax
package com.android.dialer.callrecord.impl;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTimestamp;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.MediaRecorder;
import android.media.audiopolicy.AudioMix;
import android.media.audiopolicy.AudioMixingRule;
import android.media.audiopolicy.AudioPolicy;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.util.Log;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.function.Consumer;

final class VoipCallRecorder {
  private static final String TAG = "VoipCallRecorder";
  private static final int RATE = 16000;
  private static final int FRAME_SAMPLES = RATE / 50;
  private static final int MIX_DELAY = RATE / 5;

  private final AudioManager audioManager;
  private final Context context;
  private final Consumer<VoipCallRecorder> onFailure;
  private AudioPolicy policy;
  private Source downlink;
  private Source microphone;
  private MediaCodec encoder;
  private MediaMuxer muxer;
  private FileOutputStream amrOutput;
  private int track = -1;
  private boolean muxerStarted;
  private volatile boolean running;
  private volatile long stopFrame;
  private Thread worker;

  VoipCallRecorder(Context context, Consumer<VoipCallRecorder> onFailure) {
    this.context = context;
    audioManager = context.getSystemService(AudioManager.class);
    this.onFailure = onFailure;
  }

  void start(ParcelFileDescriptor output, int formatChoice, int callUid) throws IOException {
    try {
      AudioMixingRule rule = new AudioMixingRule.Builder()
          .addRule(new AudioAttributes.Builder()
              .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).build(),
              AudioMixingRule.RULE_MATCH_ATTRIBUTE_USAGE)
          .addMixRule(AudioMixingRule.RULE_MATCH_UID, callUid)
          .build();
      AudioMix mix = new AudioMix.Builder(rule)
          .setFormat(new AudioFormat.Builder().setSampleRate(RATE)
              .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
              .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
          .setRouteFlags(AudioMix.ROUTE_FLAG_LOOP_BACK | AudioMix.ROUTE_FLAG_RENDER)
          .build();
      policy = new AudioPolicy.Builder(context).addMix(mix).build();
      if (audioManager.registerAudioPolicy(policy) != AudioManager.SUCCESS) {
        throw new IOException("Cannot register software call capture policy");
      }
      downlink = new Source(policy.createAudioRecordSink(mix));
      microphone = new Source(new AudioRecord.Builder()
          .setAudioSource(MediaRecorder.AudioSource.MIC).setPrivacySensitive(false)
          .setAudioFormat(new AudioFormat.Builder().setSampleRate(RATE)
              .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
              .setChannelMask(AudioFormat.CHANNEL_IN_MONO).build())
          .setBufferSizeInBytes(RATE * 2).build());

      boolean amr = formatChoice == 0;
      MediaFormat format = MediaFormat.createAudioFormat(
          amr ? MediaFormat.MIMETYPE_AUDIO_AMR_WB : MediaFormat.MIMETYPE_AUDIO_AAC, RATE, 1);
      format.setInteger(MediaFormat.KEY_BIT_RATE, amr ? 23850 : 32000);
      if (!amr) {
        format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        muxer = new MediaMuxer(output.getFileDescriptor(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
      } else {
        amrOutput = new ParcelFileDescriptor.AutoCloseOutputStream(
            ParcelFileDescriptor.dup(output.getFileDescriptor()));
        amrOutput.write(new byte[] {'#', '!', 'A', 'M', 'R', '-', 'W', 'B', '\n'});
      }
      encoder = MediaCodec.createEncoderByType(format.getString(MediaFormat.KEY_MIME));
      encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
      encoder.start();
      microphone.record.startRecording();
      downlink.record.startRecording();
      running = true;
      worker = new Thread(this::record, "voip-call-recorder");
      worker.start();
      Log.i(TAG, "Started software call recording for uid " + callUid);
    } catch (IOException | RuntimeException error) {
      close();
      throw error;
    }
  }

  void stop() {
    stopFrame = frameAt(System.nanoTime());
    running = false;
    if (worker != null) {
      boolean interrupted = false;
      while (worker.isAlive()) {
        try {
          worker.join();
        } catch (InterruptedException error) {
          interrupted = true;
        }
      }
      if (interrupted) Thread.currentThread().interrupt();
    }
  }

  private void record() {
    boolean failed = false;
    long firstFrame = -1;
    long nextFrame = -1;
    long deadline = SystemClock.elapsedRealtime() + 3000;
    try {
      while (running) {
        downlink.read();
        microphone.read();
        if (nextFrame == -1) {
          if (downlink.firstFrame == -1 || microphone.firstFrame == -1) {
            if (SystemClock.elapsedRealtime() > deadline) {
              throw new IOException("Software call capture did not start");
            }
            SystemClock.sleep(5);
            continue;
          }
          firstFrame = nextFrame = Math.min(downlink.firstFrame, microphone.firstFrame);
        }
        long available = frameAt(System.nanoTime()) - MIX_DELAY;
        while (running && nextFrame + FRAME_SAMPLES <= available) {
          encode(nextFrame, firstFrame);
          nextFrame += FRAME_SAMPLES;
        }
        SystemClock.sleep(5);
      }
      if (firstFrame != -1) {
        downlink.read();
        microphone.read();
        while (nextFrame + FRAME_SAMPLES <= stopFrame) {
          encode(nextFrame, firstFrame);
          nextFrame += FRAME_SAMPLES;
        }
      }
      long endTime = firstFrame == -1 ? 0 : (nextFrame - firstFrame) * 1000000 / RATE;
      long endDeadline = SystemClock.elapsedRealtime() + 3000;
      int input;
      while ((input = encoder.dequeueInputBuffer(10000)) < 0) {
        drain(false);
        if (SystemClock.elapsedRealtime() > endDeadline) {
          throw new IOException("Encoder did not accept end of stream");
        }
      }
      encoder.queueInputBuffer(input, 0, 0, endTime, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
      drain(true);
    } catch (IOException | RuntimeException error) {
      Log.e(TAG, "Software call recording failed", error);
      failed = true;
    } finally {
      running = false;
      close();
      if (failed) onFailure.accept(this);
    }
  }

  private void encode(long frame, long firstFrame) throws IOException {
    long deadline = SystemClock.elapsedRealtime() + 3000;
    int input;
    while ((input = encoder.dequeueInputBuffer(10000)) < 0) {
      drain(false);
      if (SystemClock.elapsedRealtime() > deadline) throw new IOException("Encoder stalled");
    }
    ByteBuffer buffer = encoder.getInputBuffer(input).order(ByteOrder.LITTLE_ENDIAN);
    buffer.clear();
    for (int i = 0; i < FRAME_SAMPLES; i++) {
      int sample = downlink.sample(frame + i) + microphone.sample(frame + i);
      buffer.putShort((short) (sample / 2));
    }
    encoder.queueInputBuffer(input, 0, FRAME_SAMPLES * 2,
        (frame - firstFrame) * 1000000 / RATE, 0);
    drain(false);
  }

  private void drain(boolean endOfStream) throws IOException {
    MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
    long deadline = SystemClock.elapsedRealtime() + 3000;
    for (;;) {
      int index = encoder.dequeueOutputBuffer(info, endOfStream ? 10000 : 0);
      if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
        if (muxer != null) {
          track = muxer.addTrack(encoder.getOutputFormat());
          muxer.start();
          muxerStarted = true;
        }
      } else if (index >= 0) {
        ByteBuffer buffer = encoder.getOutputBuffer(index);
        if (info.size > 0 && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
          buffer.position(info.offset);
          buffer.limit(info.offset + info.size);
          if (amrOutput != null) {
            byte[] bytes = new byte[info.size];
            buffer.get(bytes);
            amrOutput.write(bytes);
          } else {
            muxer.writeSampleData(track, buffer, info);
          }
        }
        encoder.releaseOutputBuffer(index, false);
        if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return;
      } else if (index == MediaCodec.INFO_TRY_AGAIN_LATER && !endOfStream) {
        return;
      }
      if (endOfStream && SystemClock.elapsedRealtime() > deadline) {
        throw new IOException("Encoder did not finish");
      }
    }
  }

  private void close() {
    if (downlink != null) downlink.close();
    if (microphone != null) microphone.close();
    if (policy != null) {
      audioManager.unregisterAudioPolicy(policy);
      policy = null;
    }
    if (encoder != null) {
      try { encoder.stop(); } catch (IllegalStateException ignored) {}
      encoder.release();
      encoder = null;
    }
    if (muxer != null) {
      try { if (muxerStarted) muxer.stop(); } catch (IllegalStateException error) {
        Log.w(TAG, "Could not finish recording container", error);
      }
      muxer.release();
      muxer = null;
    }
    if (amrOutput != null) {
      try { amrOutput.close(); } catch (IOException error) {
        Log.w(TAG, "Could not close recording", error);
      }
      amrOutput = null;
    }
  }

  private static long frameAt(long nanos) {
    return nanos / 1000000000 * RATE + nanos % 1000000000 * RATE / 1000000000;
  }

  private static final class Source {
    // Align both capture clocks before mixing, including after route changes.
    final AudioRecord record;
    final short[] samples = new short[RATE * 2];
    final long[] positions = new long[RATE * 2];
    final short[] input = new short[FRAME_SAMPLES];
    final AudioTimestamp timestamp = new AudioTimestamp();
    long framesRead;
    long firstFrame = -1;
    long origin = -1;

    Source(AudioRecord record) throws IOException {
      this.record = record;
      if (record == null || record.getState() != AudioRecord.STATE_INITIALIZED) {
        if (record != null) record.release();
        throw new IOException("Cannot initialize software call capture");
      }
      Arrays.fill(positions, -1);
    }

    void read() throws IOException {
      for (int attempt = 0; attempt < 32; attempt++) {
        int count = record.read(input, 0, input.length, AudioRecord.READ_NON_BLOCKING);
        if (count < 0) throw new IOException("Audio capture read failed: " + count);
        if (count == 0) return;
        if (record.getTimestamp(timestamp, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS) {
          origin = frameAt(timestamp.nanoTime) - timestamp.framePosition;
        } else if (origin == -1) {
          origin = frameAt(System.nanoTime()) - framesRead - count;
        }
        long frame = origin + framesRead;
        if (firstFrame == -1) firstFrame = frame;
        for (int i = 0; i < count; i++) {
          int slot = (int) ((frame + i) % samples.length);
          samples[slot] = input[i];
          positions[slot] = frame + i;
        }
        framesRead += count;
      }
    }

    int sample(long frame) {
      int slot = (int) (frame % samples.length);
      return positions[slot] == frame ? samples[slot] : 0;
    }

    void close() {
      try { record.stop(); } catch (IllegalStateException ignored) {}
      record.release();
    }
  }
}
