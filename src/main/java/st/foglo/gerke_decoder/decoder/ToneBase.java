package st.foglo.gerke_decoder.decoder;

import st.foglo.gerke_decoder.decoder.sliding_line.WeightBase;

public abstract class ToneBase {

    public final int k;
    public final int rise;
    public final int drop;
    
    public ToneBase(int k, int rise, int drop) {
        this.k = k;
        this.rise = rise;
        this.drop = drop;
        if (k < rise || k > drop) {
            throw new RuntimeException();
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
