package st.foglo.gerke_decoder.decoder;

import st.foglo.gerke_decoder.decoder.sliding_line.WeightBase;
import st.foglo.gerke_decoder.decoder.sliding_line.WeightDot;
import st.foglo.gerke_decoder.plot.HistEntries;

/**
 * Represents a dot.
 */
public final class Dot extends ToneBase {
    
    private static final int KEY = 1;

	public Dot(int k, int rise, int drop) {
        this(k, rise, rise, drop, drop, 0.0);
    }

    public Dot(int k, int rise, int riseN, int drop, int dropN, double strength) {
        super(k, rise, riseN, drop, dropN, strength, KEY);
    }

    public Dot(int k, int rise, int drop, HistEntries histEntries) {
        super(k, rise, drop);
        if (histEntries != null) {
            histEntries.addEntry(1, drop-rise);
        }
    }

    public Dot(
            int k,
            int jDot,
            double[] sig) {
        super(findRise(k, sig, jDot), findDrop(k, sig, jDot));
    }

    private static int findRise(int k, double[] sig, int jDot) {
        WeightBase w = new WeightDot(0);
        double uMax = 0.0;
        int qBest = k - jDot;
        for (int q = k - 2*jDot; q < k - jDot/2; q++) {    // q is slice index

            final TwoDoubles u = lsq(sig, q, jDot, w);
            if (u.b > uMax) {
                uMax = u.b;
                qBest = q;
            }
        }
        return qBest;
    }

    private static int findDrop(int k, double[] sig, int jDot) {
        WeightBase w = new WeightDot(0);
        double uMin = 0.0;
        int qBest = k + jDot;
        for (int q = k + jDot/2; q < k + 2*jDot; q++) {    // q is slice index

            final TwoDoubles u = lsq(sig, q, jDot, w);
            if (u.b < uMin) {
                uMin = u.b;
                qBest = q;
            }
        }
        return qBest;
    }


}
