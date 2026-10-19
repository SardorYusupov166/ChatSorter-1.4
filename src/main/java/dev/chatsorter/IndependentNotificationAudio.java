package dev.chatsorter;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.sound.OggAudioStream;
import net.minecraft.client.sound.Sound;
import net.minecraft.client.sound.WeightedSoundSet;
import net.minecraft.registry.Registries;
import net.minecraft.resource.ResourceManager;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.random.Random;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.SourceDataLine;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Plays selected Minecraft sound assets through Java Sound, outside Minecraft's volume mixer. */
final class IndependentNotificationAudio {
    private static final ThreadPoolExecutor AUDIO = new ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(8), task -> {
            Thread thread = new Thread(task, "ChatSorter-notification-audio");
            thread.setDaemon(true);
            return thread;
        }, new ThreadPoolExecutor.DiscardOldestPolicy()
    );

    static void play(String soundEventId, float volume) {
        MinecraftClient client = MinecraftClient.getInstance();
        Identifier eventId = Identifier.tryParse(soundEventId);
        if (eventId == null) return;

        WeightedSoundSet soundSet = client.getSoundManager().get(eventId);
        if (soundSet == null) {
            playMinecraftFallback(client, eventId, volume);
            return;
        }
        Sound selected = soundSet.getSound(Random.create());
        if (selected == null) {
            playMinecraftFallback(client, eventId, volume);
            return;
        }

        Identifier location = selected.getLocation();
        Identifier resourceId = Identifier.of(location.getNamespace(), "sounds/" + location.getPath() + ".ogg");
        ResourceManager resources = client.getResourceManager();
        float gain = Math.max(0.0F, Math.min(1.0F, volume));
        AUDIO.execute(() -> {
            if (!decodeAndPlay(resources, resourceId, gain)) {
                playMinecraftFallback(client, eventId, gain);
            }
        });
    }

    private static boolean decodeAndPlay(ResourceManager resources, Identifier resourceId, float volume) {
        try (InputStream input = resources.open(resourceId);
             OggAudioStream stream = new OggAudioStream(input)) {
            AudioFormat format = stream.getFormat();
            ByteBuffer decoded = stream.readAll();
            byte[] pcm = new byte[decoded.remaining()];
            decoded.get(pcm);
            applyVolume(pcm, format, volume);

            SourceDataLine line = AudioSystem.getSourceDataLine(format);
            try {
                line.open(format);
                line.start();
                int offset = 0;
                while (offset < pcm.length) {
                    int written = line.write(pcm, offset, pcm.length - offset);
                    if (written <= 0) throw new IllegalStateException("Audio output stopped accepting samples");
                    offset += written;
                }
                line.drain();
            } finally {
                line.close();
            }
            return pcm.length > 0;
        } catch (Exception e) {
            System.err.println("[ChatSorter] Cannot play independent notification sound " + resourceId + ": " + e);
            return false;
        }
    }

    /** Keep alerts audible if Java Sound cannot access an output device on this system. */
    private static void playMinecraftFallback(MinecraftClient client, Identifier eventId, float volume) {
        client.execute(() -> {
            if (client.player == null || volume <= 0) return;
            var event = Registries.SOUND_EVENT.get(eventId);
            if (event != null) client.player.playSound(event, volume, 1.0F);
        });
    }

    private static void applyVolume(byte[] pcm, AudioFormat format, float volume) {
        if (volume >= 0.999F) return;
        if (!AudioFormat.Encoding.PCM_SIGNED.equals(format.getEncoding()) || format.getSampleSizeInBits() != 16) {
            throw new IllegalArgumentException("Unsupported notification format: " + format);
        }

        boolean bigEndian = format.isBigEndian();
        for (int i = 0; i + 1 < pcm.length; i += 2) {
            int sample = bigEndian
                ? (short) (((pcm[i] & 0xFF) << 8) | (pcm[i + 1] & 0xFF))
                : (short) (((pcm[i + 1] & 0xFF) << 8) | (pcm[i] & 0xFF));
            int scaled = Math.round(sample * volume);
            if (bigEndian) {
                pcm[i] = (byte) (scaled >>> 8);
                pcm[i + 1] = (byte) scaled;
            } else {
                pcm[i] = (byte) scaled;
                pcm[i + 1] = (byte) (scaled >>> 8);
            }
        }
    }

    private IndependentNotificationAudio() {}
}
