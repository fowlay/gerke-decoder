package st.foglo.gerke_decoder.decoder.least_squares;

import java.util.ArrayList;
import java.util.List;
import java.util.NavigableMap;
import java.util.TreeMap;

import st.foglo.gerke_decoder.GerkeLib;
import st.foglo.gerke_decoder.GerkeDecoder;
import st.foglo.gerke_decoder.GerkeDecoder.DecoderIndex;
import st.foglo.gerke_decoder.GerkeLib.Debug;
import st.foglo.gerke_decoder.GerkeLib.Info;
import st.foglo.gerke_decoder.decoder.Dash;
import st.foglo.gerke_decoder.decoder.Decoder;
import st.foglo.gerke_decoder.decoder.DecoderBase;
import st.foglo.gerke_decoder.decoder.Dot;
import st.foglo.gerke_decoder.decoder.Node;
import st.foglo.gerke_decoder.decoder.ToneBase;
import st.foglo.gerke_decoder.decoder.TwoDoubles;
import st.foglo.gerke_decoder.decoder.sliding_line.WeightBase;
import st.foglo.gerke_decoder.decoder.sliding_line.WeightDash;
import st.foglo.gerke_decoder.decoder.sliding_line.WeightDot;
import st.foglo.gerke_decoder.format.Formatter;
import st.foglo.gerke_decoder.lib.Compute;
import st.foglo.gerke_decoder.plot.PlotEntries;
import st.foglo.gerke_decoder.wave.Wav;

/**
 * This decoder has turned out to be difficult to tune, due to its
 * many parameters.
 */
public final class LeastSquaresDecoder extends DecoderBase implements Decoder {

    public static final double THRESHOLD = 0.524;
    
    final int sigSize;
    
    final class Cluster {
        final Integer lowestKey;
        final List<Integer> members = new ArrayList<Integer>();

        Cluster(Integer a) {
            lowestKey = a;
            members.add(a);
        }

        void add(Integer b) {
            members.add(b);
        }
    }
    
    public LeastSquaresDecoder(
            double tuMillis,
            int framesPerSlice,
            double tsLength,
            int offset,
            Wav w,
            double[] sig,
            PlotEntries plotEntries,
            Formatter formatter,
            
            int sigSize,
            double[] cei,
            double[] flo,
            double ceilingMax
            
            
            ) {
        super(
                tuMillis,
                framesPerSlice,
                tsLength,
                offset,
                w,
                sig,
                plotEntries,
                formatter,
                cei,
                flo,
                ceilingMax,THRESHOLD
                );
        this.sigSize = sigSize;
    };
    
    @Override
    public void execute() {
        
        final int decoder = DecoderIndex.LEAST_SQUARES.ordinal();

        final double level = GerkeLib.getDoubleOpt(GerkeDecoder.O_LEVEL);

        final NavigableMap<Integer, ToneBase> dashes = new TreeMap<Integer, ToneBase>();

        // in theory, 0.50 .. 0.40 works better
        final int jDotSmall = (int) Math.round(0.40/tsLength);
        final int jDot = jDotSmall;

        final int jDash = (int) Math.round(1.5/tsLength);

        final int jDashSmall = (int) Math.round(1.35/tsLength);

        final double dashStrengthLimit = level*0.63;
        
        // i becomes t  <---------------------------> i becomes i 
        final double middleDipLimit = 0.75;
        
        final double dotStrengthLimit = level*0.495;
        //final double dotStrengthLimit = 0.55;
        
        // PARA, a dot has to be stronger than surrounding by this much
        final double dotPeakCrit = 1.2;
        //final double dotPeakCrit = 0.35;

        final double mergeDashesWhenCloser = 2.8/tsLength;
        final double mergeDotsWhenCloser = 0.8/tsLength;

        final WeightBase wDash = new WeightDash(jDash);
        final WeightBase wDot = new WeightDot(jDot);  // arg ignored really
        //final WeightBase w2 = new WeightTwoDots(jDash);
        //double prevB = Double.MAX_VALUE;
        
        final int reductionDot = (int) Math.round(80.0*jDot/100);

        // scan for candidate dashes
        TwoDoubles prevD = new TwoDoubles(0.0, Double.MAX_VALUE);
        
        for (int k = 0 + jDash; k < sigSize - jDash; k++) {
            final TwoDoubles r = lsq(sig, k, jDash, wDash);

            if (prevD.b >= 0.0 && r.b < 0.0) {
                // just passed a maximum

                final int kBest;
                //final double ampRange;
                final double dashStrength;
                
                if (prevD.b > -r.b) {
                    // slope was greater at previous k
                    kBest = k;
                    //ampRange = cei[kBest] - flo[kBest];
                    dashStrength = relStrength(r.a, cei[kBest], flo[kBest]);
                } 
                else {
                    // slope is greater at this k
                    kBest = k-1;
                    //ampRange = cei[kBest] - flo[kBest];
                    dashStrength = relStrength(prevD.a, cei[kBest], flo[kBest]);
                }

                if (dashStrength > dashStrengthLimit) {
                    
                    final TwoDoubles aa = lsq(sig, kBest-2*jDot, jDot, wDot);
                    final TwoDoubles bb = lsq(sig, kBest, jDot, wDot);
                    final TwoDoubles cc = lsq(sig, kBest+2*jDot, jDot, wDot);
                    
                    // protect against dip in the middle
                    if (bb.a > middleDipLimit*(aa.a + cc.a)/2) {
                        
                        try {
                            // index out of bounds can happen, hence try
                            // use an average over aa, bb, cc
                            dashes.put(Integer.valueOf(kBest),
                                    new Dash(
                                            kBest, jDot, jDash, sig,
                                            r.a, true));
                        }
                        catch (Exception e) {
                            new Info("cannot create dash, k: %d", kBest);
                        }
                    }
                } 
            }
            prevD = r;
        }

        // check for clustering, merge doublets

        mergeClusters(dashes, jDot, jDash, mergeDashesWhenCloser);


        // find all dots w/o worrying about dashes

        TwoDoubles prevDot = new TwoDoubles(0.0,  Double.MAX_VALUE);

        final NavigableMap<Integer, ToneBase> dots = new TreeMap<Integer, ToneBase>();

        // TODO: the exact calculation of limits can be refined maybe
        for (int k = 0 + 3*jDot; k < sigSize - 3*jDot; k++) {
            final TwoDoubles r = lsq(sig, k, jDot, wDot);

            //new Info("%10f %10f", r.a, r.b);
            if (prevDot.b >= 0.0 && r.b < 0.0) {

                final int kBest;
                final double a;
                if (prevDot.b > -r.b) {
                    kBest = k;
                    a = r.a;
                    
                }
                else {
                    kBest = k-1;
                    a = prevDot.a;
                }

                final double dotStrength = relStrength(a, cei[kBest], flo[kBest]); 

                if (dotStrength > dotStrengthLimit) {
                    
                    final TwoDoubles u1 = lsq(sig, kBest - 2*jDot, jDot, wDot);
                    final TwoDoubles u2 = lsq(sig, kBest + 2*jDot, jDot, wDot);
                    
                    if (a/u1.a > dotPeakCrit && a/u2.a > dotPeakCrit) {
                        dots.put(Integer.valueOf(kBest), new Dot(kBest, kBest - jDot, kBest + jDot));
                    }
                }
            }
            prevDot = r;
        }

        mergeClusters(dots, jDot, jDash, mergeDotsWhenCloser);

        // remove dashes if there are two competing dots

        final List<Integer> removals = new ArrayList<Integer>();
        for (Integer key : dashes.navigableKeySet()) {

            final Integer k1 = dots.lowerKey(key);
            final Integer k2 = dots.higherKey(key);

            if (k1 != null && k2 != null &&
                    key-jDash < k1 && k1 < key-jDot && key+jDot < k2 && k2 < key+jDash) {
                removals.add(key);
            }
        }

        // remove dots if there is already a dash
        removals.clear();
        for (Integer key : dots.navigableKeySet()) {
            final Integer k1 = dashes.floorKey(key);
            final Integer k2 = dashes.higherKey(key);

            if ((k1 != null && key - k1 < jDashSmall) || (k2 != null && k2 - key < jDashSmall)) {
                removals.add(key);
            }
        }

        for (Integer m : removals) {
            dots.remove(m);
        }

        // merge the dots to the dashes
        for (Integer key : dots.keySet()) {
            dashes.put(key, dots.get(key));
        }


        Node p = Node.tree;
        int qCharBegin = -999999;
        Integer prevKey = null;
        for (Integer key : dashes.navigableKeySet()) {

            if (prevKey == null) {
                qCharBegin = lsqToneBegin(key, dashes, jDot);
            }


            
            
            if (prevKey == null) {
                final ToneBase tb = dashes.get(key);
                p = tb instanceof Dash ? p.newNode("-") : p.newNode(".");
                lsqPlotHelper(key, tb, jDot);

            }
            else if (prevKey != null) {
                
                final int toneDistSlices =
                        toneDist(prevKey, key, dashes, jDash, jDot, reductionDot);
                
                if (toneDistSlices > GerkeDecoder.WORD_SPACE_LIMIT[decoder]/tsLength) {
                    
                    //formatter.add(true, p.text, -1);
                    final int ts =
                            GerkeLib.getFlag(GerkeDecoder.O_TSTAMPS) ?
                            offset + (int) Math.round(key*tsLength*tuMillis/1000) : -1;
                    formatter.add(true, p.text, ts);
                    wpm.chCus += p.nTus;
                    wpm.spCusW += 7;
                    wpm.spTicksW += lsqToneBegin(key, dashes, jDot) - lsqToneEnd(prevKey, dashes, jDot);
                    
                    p = Node.tree;
                    final ToneBase tb = dashes.get(key);
                    if (tb instanceof Dash) {
                        p = p.newNode("-");
                    }
                    else {
                        p = p.newNode(".");
                    }
                    wpm.chTicks += lsqToneEnd(prevKey, dashes, jDot) - qCharBegin;
                    qCharBegin = lsqToneBegin(key, dashes, jDot);
                    lsqPlotHelper(key, tb, jDot);
                }
                else if (toneDistSlices > GerkeDecoder.CHAR_SPACE_LIMIT[decoder]/tsLength) {
                    formatter.add(false, p.text, -1);
                    wpm.chCus += p.nTus;
                    wpm.spCusC += 3;
                    
                    wpm.spTicksC += lsqToneBegin(key, dashes, jDot) - lsqToneEnd(prevKey, dashes, jDot);

                    p = Node.tree;
                    final ToneBase tb = dashes.get(key);
                    if (tb instanceof Dash) {
                        p = p.newNode("-");
                    }
                    else {
                        p = p.newNode(".");
                    }
                    wpm.chTicks += lsqToneEnd(prevKey, dashes, jDot) - qCharBegin;
                    qCharBegin = lsqToneBegin(key, dashes, jDot);
                    lsqPlotHelper(key, tb, jDot);
                }
                else {
                    final ToneBase tb = dashes.get(key);
                    p = tb instanceof Dash ? p.newNode("-") : p.newNode(".");  
                    lsqPlotHelper(key, tb, jDot);
                }
            }

            prevKey = key;
        }

        if (p != Node.tree) {
            formatter.add(true, p.text, -1);
            formatter.newLine();
            wpm.chCus += p.nTus;
            wpm.chTicks += lsqToneEnd(prevKey, dashes, jDot) - qCharBegin;
        }

        wpm.report();

    }
    
    /**
     * It is trusted that 'tones' is dashes-only or dots-only
     * 
     * @param tones
     * @param jX
     * @param ticks
     */
    private void mergeClusters(
            NavigableMap<Integer, ToneBase> tones,
            int jDot,
            int jDash,
            double ticks) {
        final List<Cluster> clusters = new ArrayList<Cluster>();
        for (Integer key : tones.navigableKeySet()) {
                if (clusters.isEmpty()) {
                    clusters.add(new Cluster(key));
                }
                else if (key - clusters.get(clusters.size() - 1).lowestKey < ticks) {
                    clusters.get(clusters.size() - 1).add(key);
                    boolean isDot = tones.get(key) instanceof Dot;
                    final String ss = isDot ? "dots" : "dashes";
                    new Debug("clustering %s at t: %f", ss, 0.008005*key);
                }
                else {
                    clusters.add(new Cluster(key));
                }
        }

        for (Cluster c : clusters) {
            if (c.members.size() > 1) {
                int kSum = 0;
                boolean isDot = false;
                
                int minRise = 0;
                int maxDrop = 0;
                double sumCeiling = 0.0;

                for (Integer kk : c.members) {
                    
                    final ToneBase tone = tones.get(kk);
                    if (tone instanceof Dot) {
                        isDot = true;
                    }
                    else {
                        final Dash dash = ((Dash) tone);
                        minRise += Compute.iMin(minRise, dash.rise);
                        maxDrop += Compute.iMax(maxDrop, dash.drop);
                        sumCeiling += dash.ceiling;
                    }

                    tones.remove(kk);
                    kSum += kk.intValue();
                }
                final int newK = (int) Math.round(((double) kSum)/c.members.size());
                
                if (isDot) {
                    tones.put(Integer.valueOf(newK), new Dot(newK, newK - jDot, newK + jDot));
                }
                else {
                    tones.put(Integer.valueOf(newK),
                            new Dash(newK,
                                    minRise,
                                    maxDrop,
                                    sig,
                                    sumCeiling/c.members.size(), false));
                }
            }
        }
    }

    private double relStrength(double x, double ceiling, double floor) {
        return (x - floor)/(ceiling - floor);
    }
    
    private int toneDist(
            Integer k1,
            Integer k2,
            NavigableMap<Integer, ToneBase> tones,
            int jDash,
            int jDot,
            int reductionDot) {

        final ToneBase t1 = tones.get(k1);
        final ToneBase t2 = tones.get(k2);

        int reduction = 0;

        if (t1 instanceof Dot) {
            reduction += reductionDot;
        }
        else if (t1 instanceof Dash) {
            reduction += ((Dash) t1).drop - ((Dash) t1).k;
        }
        
        if (t2 instanceof Dot) {
            reduction += reductionDot;
        }
        else if (t2 instanceof Dash) {
            reduction += ((Dash) t2).k - ((Dash) t2).rise;
        }

        return k2 - k1 - reduction;
    }
}
