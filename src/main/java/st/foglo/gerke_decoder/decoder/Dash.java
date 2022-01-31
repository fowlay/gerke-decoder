package st.foglo.gerke_decoder.decoder;

import java.util.NavigableMap;

import st.foglo.gerke_decoder.decoder.sliding_line.WeightBase;
import st.foglo.gerke_decoder.decoder.sliding_line.WeightDot;

/**
 * Represents a dash, centered at index k. Indexes for the rise and drop are
 * specified explicitly.
 */
public final class Dash extends ToneBase {

    public final double ceiling;
    
    public Dash(int k, int rise, int drop) {
        super(k, rise, drop);
        this.ceiling = 0.0;
    }

    public Dash(
            int k,
            int jDot,
            int jDotSmall,
            int jDash,
            double[] sig,
            double ceiling,
            NavigableMap<Integer, ToneBase> tones) {
        super(findRise(k, sig, jDash, jDot), findDrop(k, sig, jDash, jDot));
        this.ceiling = ceiling;
        if (tones != null) {
            tones.put(Integer.valueOf(this.k), this);
        }
    }
    
    public Dash(
            int k,
            int jDot,
            int jDotSmall,
            int jDash,
            double[] sig,
            double ceiling,
            boolean improve) {
        super(k,
                findRise(k, sig, jDash, jDot),
                findDrop(k, sig, jDash, jDot))
        ;
        this.ceiling = ceiling;
    }
    
    


    private static int findDrop(int k, double[] sig, int jDash, int jDot) {
        WeightBase w = new WeightDot(0);
        double uMin = 0.0;
        int qBest = k + jDash;
        for (int q = k + jDash - jDot; q < k + jDash + jDot; q++) {    // q is slice index
            
            final TwoDoubles u = lsq(sig, q, jDot, w);
            if (u.b < uMin) {
                uMin = u.b;
                qBest = q;
            }
        }
        return qBest;
    }
    
    private static int findRise(int k, double[] sig, int jDash, int jDot) {
        WeightBase w = new WeightDot(0);
        double uMax = 0.0;
        int qBest = k - jDash;
        for (int q = k - jDash - jDot; q < k - jDash + jDot; q++) {    // q is slice index
            
            final TwoDoubles u = lsq(sig, q, jDot, w);
            if (u.b > uMax) {
                uMax = u.b;
                qBest = q;
            }
        }
        return qBest;
    }

//    private static int dashRise(int k, double[] sig, int jDot, int jDash, double ceiling, boolean improve) {
//
//        if (!improve) {
//            return k - jDash;
//        } 
//        else {
//            int bestRise;
//            try {
//                final double q = 0.5;
//                final WeightBase w = new WeightDot(jDot);
//                final TwoDoubles x = lsq(sig, k - jDash, jDot, w);
//                final int jx = (int) Math.round((ceiling - q - x.a)/x.b);
//                // new Debug("jDash: %d, jx: %d, jy: %d", jDash, jx, jy);
//                final int jxAbs = jx < 0 ? -jx : jx;
//                bestRise = jxAbs > 2*jDot ? k - jDash : k - jDash + jx;
//            }
//            catch (Exception e) {
//                bestRise = k - jDash;
//            }
//
//            return bestRise;
//        }
//    }
//        
//
//    private static int dashDrop(int k, double[] sig, int jDot, int jDash, double ceiling, boolean improve) {
//        if (!improve) {
//            return k + jDash;
//        } 
//        else {
//            int bestDrop;
//            try {
//                final double q = 0.5;
//                final WeightBase w = new WeightDot(jDot);
//                TwoDoubles y = lsq(sig, k + jDash, jDot, w);
//                final int jy = (int) Math.round((ceiling - q - y.a)/y.b);
//                // new Debug("jDash: %d, jx: %d, jy: %d", jDash, jx, jy);
//                final int jyAbs = jy < 0 ? -jy : jy;
//                bestDrop = jyAbs > 2*jDot ? k + jDash : k + jDash + jy;
//            }
//            catch (Exception e) {
//                bestDrop = k + jDash;
//            }
//            return bestDrop;
//        }
//    }
}
