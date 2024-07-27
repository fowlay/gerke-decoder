package st.foglo.gerke_decoder.decoder;

import java.util.HashSet;
import java.util.Set;

import st.foglo.gerke_decoder.decoder.sliding_line.WeightBase;
import st.foglo.gerke_decoder.decoder.sliding_line.WeightDot;
import st.foglo.gerke_decoder.lib.Compute;
import st.foglo.gerke_decoder.plot.HistEntries;

/**
 * Represents a dot.
 */
public final class Dot extends ToneBase {
    
    private static final int KEY = 1;

	public Dot(int k, int rise, int drop) {
        super(k, rise, drop);
    }
    
    public Dot(int rise, int drop) {
        super(Compute.iAve(rise, drop), rise, drop);
    }
    
    public Dot(int rise, int drop, double strength) {
        super(rise, drop, strength, KEY);
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
