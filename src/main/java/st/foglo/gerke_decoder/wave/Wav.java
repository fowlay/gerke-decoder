package st.foglo.gerke_decoder.wave;

import java.io.File;
import java.io.IOException;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;

import st.foglo.gerke_decoder.GerkeDecoder;
import st.foglo.gerke_decoder.GerkeLib;
import st.foglo.gerke_decoder.GerkeLib.Death;
import st.foglo.gerke_decoder.GerkeLib.Info;

/**
 * Read a WAV file into a short[] array.
 */
public final class Wav {

    final String file;                   // file path

    final AudioInputStream ais;
    final AudioFormat af;
    public final long frameLength;       // total nof. frames

    public final int frameRate;          // frames/s
    private final int offset;            // offset (s)
    private final int offsetFrames;      // offset as nof. frames
    public final int length;             // length (s)
    public final short[] wav;            // signal values
    public final int nofFrames;          // nof. frames == length of wav

    public Wav() throws IOException, UnsupportedAudioFileException {

        if (GerkeLib.nofArguments() != 1) {
            new GerkeLib.Death("expecting one filename argument, try -h for help");
        }
        this.file = GerkeLib.getArgument(0);

        this.ais = AudioSystem.getAudioInputStream(new File(file));
        this.af = ais.getFormat();
        new Info("audio format: %s", af.toString());

        this.frameRate = Math.round(af.getFrameRate());
        new Info("frame rate: %d", frameRate);

        final int nch = af.getChannels();  // needed in reading, localize?
        new Info("nof. channels: %d", nch);

        final int bpf = af.getFrameSize();
        if (bpf == AudioSystem.NOT_SPECIFIED) {
            new Death("bytes per frame is NOT SPECIFIED");
        }
        else {
            new Info("bytes per frame: %d", bpf);
        }
        if (af.isBigEndian()) {
            new Death("cannot handle big-endian WAV file");
        }


        this.frameLength = ais.getFrameLength();
        new Info(".wav file length: %.1f s", (double)frameLength/frameRate);
        new Info("nof. frames: %d", frameLength);

        // find out nof frames to go in array

        this.offset = GerkeLib.getIntOpt(GerkeDecoder.O_OFFSET);
        this.length = GerkeLib.getIntOpt(GerkeDecoder.O_LENGTH);

        if (offset < 0) {
            new Death("offset cannot be negative");
        }

        this.offsetFrames = offset*frameRate;
        
        if (length*frameRate > frameLength) {
        	new Death("option -l (seconds) must not exceed wave file length");
        }
        
        this.nofFrames = length == -1 ? ((int) (frameLength - offsetFrames)) :
        	(int) Math.min(length*frameRate, frameLength - offsetFrames);
        
        if (length*frameRate > frameLength - offsetFrames) {
        	new GerkeLib.Warning("option -l too large, using value: %d",
        			             ((int)(frameLength - offsetFrames)/frameRate));
        }

        if (nofFrames < 0) {
            new Death("offset too large, WAV file length is: %f s", (double)frameLength/frameRate);
        }

        this.wav = new short[nofFrames];

        int frameCount = 0;

        if (bpf == 1 && nch == 1) {
            final int blockSize = frameRate;
            for (int k = 0; true; k += blockSize) {
                final byte[] b = new byte[blockSize];
                final int nRead = ais.read(b, 0, blockSize);
                if (k >= offsetFrames) {
                    if (nRead == -1 || frameCount == nofFrames) {
                        break;
                    }
                    for (int j = 0; j < nRead && frameCount < nofFrames; j++) {
                        wav[k - offsetFrames + j] = (short) (100*b[j]);
                        frameCount++;
                    }
                }
            }
        }
        else if (bpf == 2 && nch == 1) {
            final int blockSize = bpf*frameRate;
            for (int k = 0; true; k += blockSize) {
                final byte[] b = new byte[blockSize];
                final int nRead = ais.read(b, 0, blockSize);
                if (k >= offsetFrames*bpf) {
                    if (nRead == -1 || frameCount == nofFrames) {
                        break;
                    }
                    for (int j = 0; j < nRead/bpf && frameCount < nofFrames; j++) {
                        wav[k/bpf - offsetFrames + j] =
                                (short) (256*b[bpf*j+1] + (b[bpf*j] < 0 ? (b[bpf*j] + 256) : b[bpf*j]));
                        frameCount++;
                    }
                }
            }
        }
        else if (bpf == 3 && nch == 1) {
         // 1 channel, 24 bits per channel, drop the least significant byte
            final int blockSize = bpf*frameRate;
            for (int k = 0; true; k += blockSize) {
                final byte[] b = new byte[blockSize];
                final int nRead = ais.read(b, 0, blockSize);
                if (k >= offsetFrames*bpf) {
                    if (nRead == -1 || frameCount == nofFrames) {
                        break;
                    }
                    for (int j = 0; j < nRead/bpf && frameCount < nofFrames; j++) {
                        wav[k/bpf - offsetFrames + j] =
                                (short) (256*b[bpf*j+2] + (b[bpf*j+1] < 0 ? (b[bpf*j+1] + 256) : b[bpf*j+1]));
                        frameCount++;
                    }
                }
            }
        }
        else if (bpf == 4 && nch == 2) {
            final int blockSize = bpf*frameRate;
            for (int k = 0; true; k += blockSize) {
                final byte[] b = new byte[blockSize];
                final int nRead = ais.read(b, 0, blockSize);
                if (k >= offsetFrames*bpf) {
                    if (nRead == -1 || frameCount == nofFrames) {
                        break;
                    }
                    for (int j = 0; j < nRead/bpf && frameCount < nofFrames; j++) {
                        final int left = (256*b[bpf*j+1] + (b[bpf*j] < 0 ? (b[bpf*j] + 256) : b[bpf*j]));
                        final int right = (256*b[bpf*j+3] + (b[bpf*j+2] < 0 ? (b[bpf*j+2] + 256) : b[bpf*j+2]));
                        wav[k/bpf - offsetFrames + j] =
                                (short) ((left + right)/2);
                        frameCount++;
                    }
                }
            }
        }

        else if (bpf == 6 && nch == 2) {
            // 2 channels, 24 bits per channel, drop the least significant bytes
            final int blockSize = bpf*frameRate;
            for (int k = 0; true; k += blockSize) {
                final byte[] b = new byte[blockSize];
                final int nRead = ais.read(b, 0, blockSize);
                if (k >= offsetFrames*bpf) {
                    if (nRead == -1 || frameCount == nofFrames) {
                        break;
                    }
                    for (int j = 0; j < nRead/bpf && frameCount < nofFrames; j++) {
                        final int left = (256*b[bpf*j+2] + (b[bpf*j+1] < 0 ? (b[bpf*j+1] + 256) : b[bpf*j+1]));
                        final int right = (256*b[bpf*j+5] + (b[bpf*j+4] < 0 ? (b[bpf*j+4] + 256) : b[bpf*j+4]));
                        wav[k/bpf - offsetFrames + j] =
                                (short) ((left + right)/2);
                        frameCount++;
                    }
                }
            }
        }
        else {
            ais.close();
            new Death("cannot handle bytesPerFrame: %d, nofChannels: %d", bpf, nch);
        }
        ais.close();
    }

    public double secondsFromSliceIndex(int q, int framesPerSlice) {
        return (((double) q)*framesPerSlice + offsetFrames)/frameRate;
    }

    public int wavIndexFromSeconds(double t) {
        return (int) Math.round(t*frameRate - offsetFrames);
    }
}
