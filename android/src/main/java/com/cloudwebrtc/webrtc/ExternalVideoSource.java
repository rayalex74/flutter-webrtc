package com.cloudwebrtc.webrtc;

import com.cloudwebrtc.webrtc.utils.ConstraintsArray;
import com.cloudwebrtc.webrtc.utils.ConstraintsMap;
import com.cloudwebrtc.webrtc.video.LocalVideoTrack;

import org.webrtc.JavaI420Buffer;
import org.webrtc.MediaStream;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.VideoFrame;
import org.webrtc.VideoSource;
import org.webrtc.VideoTrack;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Android-only external video source backed by the flutter_webrtc 1.3.0
 * PeerConnectionFactory. Raw frames never cross the Flutter MethodChannel.
 */
public final class ExternalVideoSource {
  private static final AtomicReference<ExternalVideoSource> active = new AtomicReference<>();

  private final StateProvider stateProvider;
  private final VideoSource videoSource;
  private final VideoTrack videoTrack;
  private final LocalVideoTrack localVideoTrack;
  private final MediaStream mediaStream;
  private final String trackId;
  private final String streamId;
  private final AtomicBoolean frameInFlight = new AtomicBoolean(false);
  private final AtomicBoolean disposed = new AtomicBoolean(false);
  private final AtomicLong framesReceived = new AtomicLong();
  private final AtomicLong framesSubmitted = new AtomicLong();
  private final AtomicLong framesDropped = new AtomicLong();
  private volatile long firstFrameTimestampUs = -1;
  private volatile int lastWidth = 0;
  private volatile int lastHeight = 0;

  public ExternalVideoSource(StateProvider stateProvider) {
    this.stateProvider = stateProvider;
    PeerConnectionFactory factory = stateProvider.getPeerConnectionFactory();
    if (factory == null) {
      throw new IllegalStateException("PeerConnectionFactory is not initialized");
    }

    trackId = stateProvider.getNextTrackUUID();
    streamId = stateProvider.getNextStreamUUID();
    videoSource = factory.createVideoSource(false);
    videoTrack = factory.createVideoTrack(trackId, videoSource);
    if (videoTrack == null) {
      videoSource.dispose();
      throw new IllegalStateException("Failed to create external VideoTrack");
    }

    localVideoTrack = new LocalVideoTrack(videoTrack);
    videoSource.setVideoProcessor(localVideoTrack);

    mediaStream = factory.createLocalMediaStream(streamId);
    if (mediaStream == null) {
      videoTrack.dispose();
      videoSource.dispose();
      throw new IllegalStateException("Failed to create external MediaStream");
    }

    mediaStream.addTrack(videoTrack);
    stateProvider.putLocalTrack(trackId, localVideoTrack);
    stateProvider.putLocalStream(streamId, mediaStream);

    ExternalVideoSource previous = active.getAndSet(this);
    if (previous != null && previous != this) {
      previous.dispose();
    }
  }

  public String getTrackId() { return trackId; }
  public String getStreamId() { return streamId; }

  public ConstraintsMap toResultMap() {
    ConstraintsMap track = new ConstraintsMap();
    track.putBoolean("enabled", videoTrack.enabled());
    track.putString("id", trackId);
    track.putString("kind", "video");
    track.putString("label", trackId);
    track.putString("readyState", videoTrack.state().toString());
    track.putBoolean("remote", false);

    ConstraintsMap settings = new ConstraintsMap();
    settings.putString("kind", "videoinput");
    if (lastWidth > 0) settings.putInt("width", lastWidth);
    if (lastHeight > 0) settings.putInt("height", lastHeight);
    track.putMap("settings", settings.toMap());

    ConstraintsArray videoTracks = new ConstraintsArray();
    videoTracks.pushMap(track);

    ConstraintsMap result = new ConstraintsMap();
    result.putString("streamId", streamId);
    result.putString("ownerTag", "local");
    result.putArray("audioTracks", new ConstraintsArray().toArrayList());
    result.putArray("videoTracks", videoTracks.toArrayList());
    return result;
  }

  /**
   * Entry point for an in-process Android producer such as Meta DAT.
   * Expected input is tightly packed planar I420: Y, then U, then V.
   */
  public static boolean submitI420(ByteBuffer data, int width, int height, long presentationTimeUs) {
    ExternalVideoSource source = active.get();
    return source != null && source.submit(data, width, height, presentationTimeUs);
  }

  private boolean submit(ByteBuffer data, int width, int height, long presentationTimeUs) {
    framesReceived.incrementAndGet();
    if (disposed.get() || data == null || width <= 0 || height <= 0) {
      framesDropped.incrementAndGet();
      return false;
    }
    if (!frameInFlight.compareAndSet(false, true)) {
      framesDropped.incrementAndGet();
      return false;
    }

    try {
      int chromaWidth = (width + 1) / 2;
      int chromaHeight = (height + 1) / 2;
      int ySize = width * height;
      int chromaSize = chromaWidth * chromaHeight;
      int required = ySize + (2 * chromaSize);

      ByteBuffer input = data.duplicate();
      input.position(0);
      if (input.remaining() < required) {
        framesDropped.incrementAndGet();
        return false;
      }

      JavaI420Buffer buffer = JavaI420Buffer.allocate(width, height);
      copyPlane(input, 0, width, height, buffer.getDataY(), buffer.getStrideY());
      copyPlane(input, ySize, chromaWidth, chromaHeight, buffer.getDataU(), buffer.getStrideU());
      copyPlane(input, ySize + chromaSize, chromaWidth, chromaHeight, buffer.getDataV(), buffer.getStrideV());

      long timestampNs = presentationTimeUs * 1000L;
      VideoFrame frame = new VideoFrame(buffer, 0, timestampNs);
      try {
        videoSource.getCapturerObserver().onFrameCaptured(frame);
        framesSubmitted.incrementAndGet();
        lastWidth = width;
        lastHeight = height;
        if (firstFrameTimestampUs < 0) firstFrameTimestampUs = presentationTimeUs;
      } finally {
        frame.release();
      }
      return true;
    } finally {
      frameInFlight.set(false);
    }
  }

  private static void copyPlane(ByteBuffer input, int sourceOffset, int rowWidth, int rows,
                                ByteBuffer output, int outputStride) {
    ByteBuffer src = input.duplicate();
    ByteBuffer dst = output.duplicate();
    for (int row = 0; row < rows; row++) {
      src.position(sourceOffset + row * rowWidth);
      src.limit(sourceOffset + (row + 1) * rowWidth);
      dst.position(row * outputStride);
      dst.put(src);
    }
  }

  public ConstraintsMap stats() {
    ConstraintsMap map = new ConstraintsMap();
    map.putString("trackId", trackId);
    map.putString("streamId", streamId);
    map.putString("framesReceived", Long.toString(framesReceived.get()));
    map.putString("framesSubmitted", Long.toString(framesSubmitted.get()));
    map.putString("framesDropped", Long.toString(framesDropped.get()));
    map.putString("firstFrameTimestampUs", Long.toString(firstFrameTimestampUs));
    map.putInt("width", lastWidth);
    map.putInt("height", lastHeight);
    map.putBoolean("disposed", disposed.get());
    return map;
  }

  public void dispose() {
    if (!disposed.compareAndSet(false, true)) return;
    active.compareAndSet(this, null);
    videoSource.setVideoProcessor(null);
    mediaStream.removeTrack(videoTrack);
    stateProvider.removeLocalTrack(trackId);
    stateProvider.removeLocalStream(streamId);
    videoTrack.setEnabled(false);
    videoTrack.dispose();
    videoSource.dispose();
    mediaStream.dispose();
  }
}
