package st.foglo.gerke_decoder.detector;

import st.foglo.gerke_decoder.GerkeDecoder;

public final class TrigTable {
    final double[] sines;
    final double[] coses;

    public TrigTable(double f, int nFrames, int frameRate) {
        this.sines = new double[nFrames];
        this.coses = new double[nFrames];
        for (int j = 0; j < nFrames; j++) {
            final double angle = GerkeDecoder.TWO_PI*f*j/frameRate;
            sines[j] = Math.sin(angle);
            coses[j] = Math.cos(angle);
        }
    }

    public double sin(int j) {
        return sines[j];
    }

    public double cos(int j) {
        return coses[j];
    }
}
