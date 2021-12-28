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

    final String file;   // file path

    final AudioInputStream ais;
    final AudioFormat af;
    public final long frameLength;

    public final int frameRate; // frames/s
    final int offset;    // offset (s)
    public final int offsetFrames;   // offset as nof. frames
    public final int length;    // length (s)
    public final short[] wav;   // signal values

    public final int nofFrames;

    public Wav() throws IOException, UnsupportedAudioFileException {

        if (GerkeLib.nofArguments() != 1) {
            new GerkeLib.Death("expecting one filename argument, try -h for help");
        }
        file = GerkeLib.getArgument(0);

        ais = AudioSystem.getAudioInputStream(new File(file));
        af = ais.getFormat();
        new Info("audio format: %s", af.toString());

        frameRate = Math.round(af.getFrameRate());
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


        frameLength = ais.getFrameLength();
        new Info(".wav file length: %.1f s", (double)frameLength/frameRate);
        new Info("nof. frames: %d", frameLength);

        // find out nof frames to go in array

        offset = GerkeLib.getIntOpt(GerkeDecoder.O_OFFSET);
        length = GerkeLib.getIntOpt(GerkeDecoder.O_LENGTH);

        if (offset < 0) {
            new Death("offset cannot be negative");
        }

        offsetFrames = offset*frameRate;

        final int offsetFrames = GerkeLib.getIntOpt(GerkeDecoder.O_OFFSET)*frameRate;
        nofFrames = length == -1 ? ((int) (frameLength - offsetFrames)) : length*frameRate;

        if (nofFrames < 0) {
            new Death("offset too large, WAV file length is: %f s", (double)frameLength/frameRate);
        }


        wav = new short[nofFrames];

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
        else {
            ais.close();
            new Death("cannot handle bytesPerFrame: %d, nofChannels: %d", bpf, nch);
        }
        ais.close();
    }
}
