package st.foglo.gerke_decoder.decoder;

import java.util.HashSet;
import java.util.Set;

import st.foglo.gerke_decoder.decoder.sliding_line.WeightBase;
import st.foglo.gerke_decoder.lib.Compute;

public abstract class ToneBase {
    
    public final int key;

    public final int k;
    public final int rise;
    public final int drop;
    public final double strength;
    
    // Only the integrating decoder needs this
    public final Set<ToneBase> clashers = new HashSet<ToneBase>();

    public ToneBase(int rise, int drop) {
        this(Compute.iAve(rise, drop), rise, drop);
    }
    
    // For cases where k is not the average of rise and drop
    public ToneBase(int k, int rise, int drop) {
    	this(k, rise, drop, 0.0, Integer.MIN_VALUE);
    }
    
    public ToneBase(int rise, int drop, double strength) {
        this(Compute.iAve(rise, drop), rise, drop, strength, Integer.MIN_VALUE);
    }
    
    public ToneBase(int rise, int drop, double strength, int key) {
        this(Compute.iAve(rise, drop), rise, drop, strength, key);
    }
    
    public ToneBase(int k, int rise, int drop, double strength, int key) {
        this.key = key;
        this.k = k;
        this.rise = rise;
        this.drop = drop;
        this.strength = strength;
        if (k < rise || k > drop) {
            throw new IllegalArgumentException(
            		String.format("arguments: %d, %d, %d", k, rise, drop));
        }
    }

    protected static TwoDoubles lsq(double[] sig, int k, int jMax, WeightBase weight) {
        double sumW = 0.0;
        double sumJW = 0.0;
        double sumJJW = 0.0;
        double r1 = 0.0;
        double r2 = 0.0;
        for (int j = -jMax; j <= jMax; j++) {
            final double w = weight.w(j);
            sumW += w;
            sumJW += j*w;
            sumJJW += j*j*w;
            r1 += w*sig[k+j];
            r2 += j*w*sig[k+j];
        }
        double det = sumW*sumJJW - sumJW*sumJW;

        return new TwoDoubles((r1*sumJJW - r2*sumJW)/det, (sumW*r2 - sumJW*r1)/det);
    }
}
