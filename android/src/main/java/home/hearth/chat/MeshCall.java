package home.hearth.chat;

import android.annotation.SuppressLint;
import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.NoiseSuppressor;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.SecureRandom;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;

/**
 * Voice calls over the Bluetooth mesh, between two phones that are directly connected.
 *
 * <p>Audio uses the phone's voice-call path (hardware echo cancelling, earpiece or speaker). Voice is compressed
 * with Opus at 16 kbit/s when the phone has an Opus encoder (Android 10+); otherwise G.711 mu-law at 64 kbit/s,
 * which needs a short-range link. Each 40 ms frame travels as one urgent Bluetooth packet:
 * [0xA1, call id (4), sequence (2), codec (1), audio...].
 */
@SuppressLint("MissingPermission")
final class MeshCall implements BleMesh.CallSink {
    private static final String TAG = "HearthMeshCall";
    private static final int RATE_OPUS = 16000;
    private static final int RATE_PCMU = 8000;
    private static final int FRAME_MS = 40;
    private static final byte CODEC_OPUS = 1;
    private static final byte CODEC_PCMU = 2;
    private static final long RING_TIMEOUT_MS = 45_000;

    interface Listener {
        void onCallEvent(JSONObject event);
    }

    private final Context context;
    private final BleMesh mesh;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final SecureRandom random = new SecureRandom();
    private volatile Listener listener;
    private volatile Listener backgroundListener;
    private volatile String peer = "";
    private volatile String peerName = "";
    private volatile int callId;
    private volatile String state = "idle";
    private volatile byte codec = CODEC_OPUS;
    private volatile boolean muted;
    private volatile boolean speaker;
    private volatile boolean outgoing;
    private long startedAt;
    private Thread captureThread;
    private Thread playThread;
    private final LinkedBlockingDeque<byte[]> incoming = new LinkedBlockingDeque<>();
    private volatile boolean media;

    MeshCall(Context context, BleMesh mesh) {
        this.context = context.getApplicationContext();
        this.mesh = mesh;
        mesh.setCallSink(this);
    }

    void setListener(Listener listener) {
        this.listener = listener;
    }

    void setBackgroundListener(Listener listener) {
        this.backgroundListener = listener;
    }

    private void dispatch(JSONObject event) {
        Listener target = listener != null ? listener : backgroundListener;
        if (target != null) target.onCallEvent(event);
    }

    static boolean opusAvailable() {
        try {
            MediaFormat format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, RATE_OPUS, 1);
            MediaCodecList list = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
            return list.findEncoderForFormat(format) != null && list.findDecoderForFormat(withOpusHeaders(format)) != null;
        } catch (Exception e) {
            return false;
        }
    }

    /* ---------- Call control (from the app) ---------- */

    synchronized String call(String peerId, String name) {
        if (!"idle".equals(state)) return "You are already in a call.";
        if (!mesh.isDirect(peerId)) return "Bluetooth calls need them nearby (directly connected).";
        peer = peerId;
        peerName = name;
        callId = random.nextInt();
        outgoing = true;
        speaker = false;
        muted = false;
        state = "ringing";
        signal("invite", opusAvailable() ? "opus,pcmu" : "pcmu");
        main.postDelayed(ringTimeout, RING_TIMEOUT_MS);
        emit();
        return "";
    }

    synchronized void answer(boolean accept) {
        if (!"incoming".equals(state)) return;
        if (!accept) {
            signal("decline", null);
            finish("declined");
            return;
        }
        signal("accept", codec == CODEC_OPUS ? "opus" : "pcmu");
        begin();
    }

    synchronized void hangup() {
        if ("idle".equals(state)) return;
        signal("ringing".equals(state) ? "cancel" : "end", null);
        finish("ended");
    }

    void setMuted(boolean value) {
        muted = value;
        emit();
    }

    void setSpeaker(boolean value) {
        speaker = value;
        AudioManager audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (audio != null) audio.setSpeakerphoneOn(value);
        emit();
    }

    private final Runnable ringTimeout = () -> {
        synchronized (MeshCall.this) {
            if ("ringing".equals(state)) {
                signal("cancel", null);
                finish("no answer");
            }
        }
    };

    private void signal(String action, String codecs) {
        try {
            JSONObject signal = new JSONObject();
            signal.put("action", action);
            signal.put("call", callId);
            if (codecs != null) signal.put("codecs", codecs);
            mesh.sendCallSignal(peer, signal);
        } catch (JSONException ignored) {
        }
    }

    /* ---------- Signals from the other phone ---------- */

    @Override public synchronized void onCallSignal(String from, JSONObject signal) {
        String action = signal.optString("action");
        if ("lost".equals(action)) {
            if (from.equals(peer) && !"idle".equals(state)) finish("connection lost");
            return;
        }
        int id = signal.optInt("call");
        switch (action) {
            case "invite":
                if (!"idle".equals(state)) {
                    try {
                        JSONObject busy = new JSONObject();
                        busy.put("action", "busy");
                        busy.put("call", id);
                        mesh.sendCallSignal(from, busy);
                    } catch (JSONException ignored) {
                    }
                    return;
                }
                peer = from;
                peerName = nameOf(from);
                callId = id;
                outgoing = false;
                speaker = false;
                muted = false;
                codec = signal.optString("codecs").contains("opus") && opusAvailable() ? CODEC_OPUS : CODEC_PCMU;
                state = "incoming";
                main.postDelayed(ringTimeout2, RING_TIMEOUT_MS);
                emit();
                break;
            case "accept":
                if (id == callId && from.equals(peer) && "ringing".equals(state)) {
                    codec = "opus".equals(signal.optString("codecs")) ? CODEC_OPUS : CODEC_PCMU;
                    begin();
                }
                break;
            case "decline":
                if (id == callId && from.equals(peer)) finish("declined");
                break;
            case "busy":
                if (id == callId && from.equals(peer)) finish("busy");
                break;
            case "cancel":
                if (id == callId && from.equals(peer)) finish("missed");
                break;
            case "end":
                if (id == callId && from.equals(peer)) finish("ended");
                break;
            default:
                break;
        }
    }

    private final Runnable ringTimeout2 = () -> {
        synchronized (MeshCall.this) {
            if ("incoming".equals(state)) finish("missed");
        }
    };

    @Override public void onAudio(String from, byte[] packet) {
        if (!media || !from.equals(peer) || packet.length < 9) return;
        int id = ByteBuffer.wrap(packet, 1, 4).getInt();
        if (id != callId) return;
        if (incoming.size() > 8) incoming.pollFirst();
        incoming.offerLast(packet);
    }

    private String nameOf(String id) {
        try {
            org.json.JSONArray peers = mesh.state().optJSONArray("peers");
            for (int i = 0; peers != null && i < peers.length(); i++) {
                JSONObject item = peers.getJSONObject(i);
                if (id.equals(item.optString("id"))) return item.optString("name", "Someone");
            }
        } catch (JSONException ignored) {
        }
        return "Someone";
    }

    /* ---------- Audio ---------- */

    private void begin() {
        main.removeCallbacks(ringTimeout);
        main.removeCallbacks(ringTimeout2);
        state = "active";
        startedAt = System.currentTimeMillis();
        AudioManager audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (audio != null) {
            audio.setMode(AudioManager.MODE_IN_COMMUNICATION);
            audio.setSpeakerphoneOn(speaker);
        }
        incoming.clear();
        media = true;
        captureThread = new Thread(this::captureLoop, "hearth-call-mic");
        playThread = new Thread(this::playLoop, "hearth-call-speaker");
        captureThread.start();
        playThread.start();
        emit();
    }

    private synchronized void finish(String reason) {
        main.removeCallbacks(ringTimeout);
        main.removeCallbacks(ringTimeout2);
        boolean wasActive = "active".equals(state);
        long duration = wasActive ? (System.currentTimeMillis() - startedAt) / 1000 : 0;
        media = false;
        incoming.clear();
        if (captureThread != null) captureThread.interrupt();
        if (playThread != null) playThread.interrupt();
        captureThread = null;
        playThread = null;
        AudioManager audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (audio != null) {
            audio.setSpeakerphoneOn(false);
            audio.setMode(AudioManager.MODE_NORMAL);
        }
        String endedPeer = peer;
        String endedName = peerName;
        state = "idle";
        try {
            JSONObject event = new JSONObject();
            event.put("type", "call");
            event.put("state", "ended");
            event.put("reason", reason);
            event.put("peer", endedPeer);
            event.put("name", endedName);
            event.put("duration", duration);
            event.put("outgoing", outgoing);
            dispatch(event);
        } catch (JSONException ignored) {
        }
    }

    private void emit() {
        try {
            JSONObject event = new JSONObject();
            event.put("type", "call");
            event.put("state", state);
            event.put("peer", peer);
            event.put("name", peerName);
            event.put("muted", muted);
            event.put("speaker", speaker);
            event.put("outgoing", outgoing);
            event.put("codec", codec == CODEC_OPUS ? "Opus 16 kbit/s" : "G.711 64 kbit/s");
            event.put("startedAt", startedAt);
            dispatch(event);
        } catch (JSONException ignored) {
        }
    }

    JSONObject snapshot() {
        JSONObject event = new JSONObject();
        try {
            event.put("type", "call");
            event.put("state", state);
            event.put("peer", peer);
            event.put("name", peerName);
            event.put("muted", muted);
            event.put("speaker", speaker);
            event.put("outgoing", outgoing);
            event.put("startedAt", startedAt);
        } catch (JSONException ignored) {
        }
        return event;
    }

    private void captureLoop() {
        int rate = codec == CODEC_OPUS ? RATE_OPUS : RATE_PCMU;
        int frameSamples = rate * FRAME_MS / 1000;
        int minimum = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        AudioRecord record = null;
        MediaCodec encoder = null;
        AcousticEchoCanceler echo = null;
        NoiseSuppressor noise = null;
        try {
            record = new AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, rate, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, Math.max(minimum, frameSamples * 4));
            if (AcousticEchoCanceler.isAvailable()) {
                echo = AcousticEchoCanceler.create(record.getAudioSessionId());
                if (echo != null) echo.setEnabled(true);
            }
            if (NoiseSuppressor.isAvailable()) {
                noise = NoiseSuppressor.create(record.getAudioSessionId());
                if (noise != null) noise.setEnabled(true);
            }
            if (codec == CODEC_OPUS) {
                MediaFormat format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, RATE_OPUS, 1);
                format.setInteger(MediaFormat.KEY_BIT_RATE, 16000);
                format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, frameSamples * 2);
                encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS);
                encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                encoder.start();
            }
            record.startRecording();
            short[] samples = new short[frameSamples];
            byte[] pcm = new byte[frameSamples * 2];
            int sequence = 0;
            long presentation = 0;
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            while (media && !Thread.currentThread().isInterrupted()) {
                int read = 0;
                while (read < frameSamples && media) {
                    int count = record.read(samples, read, frameSamples - read);
                    if (count <= 0) break;
                    read += count;
                }
                if (read < frameSamples) continue;
                if (muted) java.util.Arrays.fill(samples, (short) 0);
                if (codec == CODEC_PCMU) {
                    byte[] out = new byte[frameSamples];
                    for (int i = 0; i < frameSamples; i++) out[i] = linearToUlaw(samples[i]);
                    send(sequence++, out);
                    continue;
                }
                ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(samples);
                int inputIndex = encoder.dequeueInputBuffer(10_000);
                if (inputIndex >= 0) {
                    ByteBuffer input = encoder.getInputBuffer(inputIndex);
                    input.clear();
                    input.put(pcm);
                    encoder.queueInputBuffer(inputIndex, 0, pcm.length, presentation, 0);
                    presentation += FRAME_MS * 1000L;
                }
                int outputIndex;
                while ((outputIndex = encoder.dequeueOutputBuffer(info, 0)) >= 0) {
                    if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && info.size > 0) {
                        ByteBuffer output = encoder.getOutputBuffer(outputIndex);
                        byte[] frame = new byte[info.size];
                        output.position(info.offset);
                        output.get(frame);
                        send(sequence++, frame);
                    }
                    encoder.releaseOutputBuffer(outputIndex, false);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Microphone stopped", e);
        } finally {
            try {
                if (record != null) {
                    record.stop();
                    record.release();
                }
            } catch (Exception ignored) {
            }
            try {
                if (encoder != null) {
                    encoder.stop();
                    encoder.release();
                }
            } catch (Exception ignored) {
            }
            if (echo != null) echo.release();
            if (noise != null) noise.release();
        }
    }

    private void send(int sequence, byte[] audio) {
        byte[] packet = new byte[8 + audio.length];
        packet[0] = BleMesh.AUDIO_MARK;
        ByteBuffer.wrap(packet, 1, 4).putInt(callId);
        packet[5] = (byte) (sequence >> 8);
        packet[6] = (byte) sequence;
        packet[7] = codec;
        System.arraycopy(audio, 0, packet, 8, audio.length);
        mesh.sendAudio(peer, packet);
    }

    private void playLoop() {
        int rate = codec == CODEC_OPUS ? RATE_OPUS : RATE_PCMU;
        AudioTrack track = null;
        MediaCodec decoder = null;
        try {
            if (codec == CODEC_OPUS) {
                MediaFormat format = withOpusHeaders(MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, RATE_OPUS, 1));
                decoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS);
                decoder.configure(format, null, null, 0);
                decoder.start();
                // The decoder may output at a different rate (often 48 kHz); the track is created from its output format.
                rate = 0;
            }
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            long presentation = 0;
            while (media && !Thread.currentThread().isInterrupted()) {
                byte[] packet = incoming.pollFirst(200, TimeUnit.MILLISECONDS);
                if (packet == null) continue;
                byte[] audio = new byte[packet.length - 8];
                System.arraycopy(packet, 8, audio, 0, audio.length);
                if (codec == CODEC_PCMU || packet[7] == CODEC_PCMU) {
                    if (track == null) track = openTrack(RATE_PCMU);
                    short[] pcm = new short[audio.length];
                    for (int i = 0; i < audio.length; i++) pcm[i] = ulawToLinear(audio[i]);
                    track.write(pcm, 0, pcm.length);
                    continue;
                }
                int inputIndex = decoder.dequeueInputBuffer(10_000);
                if (inputIndex >= 0) {
                    ByteBuffer input = decoder.getInputBuffer(inputIndex);
                    input.clear();
                    input.put(audio);
                    decoder.queueInputBuffer(inputIndex, 0, audio.length, presentation, 0);
                    presentation += FRAME_MS * 1000L;
                }
                int outputIndex;
                while ((outputIndex = decoder.dequeueOutputBuffer(info, 0)) != MediaCodec.INFO_TRY_AGAIN_LATER) {
                    if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        rate = decoder.getOutputFormat().getInteger(MediaFormat.KEY_SAMPLE_RATE);
                        if (track != null) track.release();
                        track = openTrack(rate);
                        continue;
                    }
                    if (outputIndex < 0) continue;
                    if (info.size > 0) {
                        if (track == null) track = openTrack(rate == 0 ? 48000 : rate);
                        ByteBuffer output = decoder.getOutputBuffer(outputIndex);
                        byte[] pcm = new byte[info.size];
                        output.position(info.offset);
                        output.get(pcm);
                        track.write(pcm, 0, pcm.length);
                    }
                    decoder.releaseOutputBuffer(outputIndex, false);
                }
            }
        } catch (InterruptedException ignored) {
        } catch (Exception e) {
            Log.w(TAG, "Speaker stopped", e);
        } finally {
            try {
                if (track != null) {
                    track.stop();
                    track.release();
                }
            } catch (Exception ignored) {
            }
            try {
                if (decoder != null) {
                    decoder.stop();
                    decoder.release();
                }
            } catch (Exception ignored) {
            }
        }
    }

    private AudioTrack openTrack(int rate) {
        int minimum = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        AudioTrack track = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setSampleRate(rate)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build())
                .setBufferSizeInBytes(Math.max(minimum, rate / 5 * 2))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build();
        track.play();
        return track;
    }

    /** Opus decoders need the identification header and pre-skip/seek pre-roll values (RFC 7845). */
    private static MediaFormat withOpusHeaders(MediaFormat format) {
        ByteBuffer head = ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN);
        head.put("OpusHead".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        head.put((byte) 1);
        head.put((byte) 1);
        head.putShort((short) 312);
        head.putInt(RATE_OPUS);
        head.putShort((short) 0);
        head.put((byte) 0);
        head.flip();
        format.setByteBuffer("csd-0", head);
        format.setByteBuffer("csd-1", longBuffer(312L * 1_000_000_000L / 48000));
        format.setByteBuffer("csd-2", longBuffer(80_000_000L));
        return format;
    }

    private static ByteBuffer longBuffer(long value) {
        ByteBuffer buffer = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putLong(value);
        buffer.flip();
        return buffer;
    }

    /* ---------- G.711 mu-law ---------- */

    private static byte linearToUlaw(short sample) {
        int pcm = sample;
        int sign = (pcm >> 8) & 0x80;
        if (sign != 0) pcm = -pcm;
        if (pcm > 32635) pcm = 32635;
        pcm += 0x84;
        int exponent = 7;
        for (int mask = 0x4000; (pcm & mask) == 0 && exponent > 0; mask >>= 1) exponent--;
        int mantissa = (pcm >> (exponent + 3)) & 0x0f;
        return (byte) ~(sign | (exponent << 4) | mantissa);
    }

    private static short ulawToLinear(byte value) {
        int ulaw = ~value & 0xff;
        int sign = ulaw & 0x80;
        int exponent = (ulaw >> 4) & 0x07;
        int mantissa = ulaw & 0x0f;
        int sample = ((mantissa << 3) + 0x84) << exponent;
        sample -= 0x84;
        return (short) (sign != 0 ? -sample : sample);
    }
}
