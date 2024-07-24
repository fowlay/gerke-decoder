package st.foglo.gerke_decoder.decoder;

import java.util.HashSet;
import java.util.Set;

import st.foglo.gerke_decoder.decoder.sliding_line.WeightBase;

public abstract class ToneBase {

    public final int k;
    public final int rise;
    public final int drop;
    public final double strength;
    
    // TODO, only the integrating decoder needs this
    public final Set<ToneBase> clashers = new HashSet<ToneBase>();

    public ToneBase(int k, int rise, int drop) {
    	this(k, rise, drop, 0.0);
    }
    
    public ToneBase(int k, int rise, int drop, double strength) {
        this.k = k;
        this.rise = rise;
        this.drop = drop;
        this.strength = strength;
        if (k < rise || k > drop) {
            throw new IllegalArgumentException(
            		String.format("arguments: %d, %d, %d", k, rise, drop));
        }
    }

    // TODO, who uses this? -- should use the this(...) pattern
    public ToneBase(int rise, int drop) {
        this.rise = rise;
        this.drop = drop;
        this.k = (rise + drop)/2;
        this.strength = 0.0;
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
