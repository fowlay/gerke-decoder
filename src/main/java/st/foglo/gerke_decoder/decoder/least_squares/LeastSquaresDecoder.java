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
        final int jDot = (int) Math.round(0.50/tsLength);
        final int jDotSmall = (int) Math.round(0.40/tsLength);

        final int jDash = (int) Math.round(1.5/tsLength);

        // if too low, dots will be interpreted as dashes
        final double dashStrengthLimit = level*0.6;
                //level*0.77;
        
        // two dots may become a dash  <------|------> dots survive  
        final double middleDipLimit = 0.85; 
                // 0.8;
        
        final double dotStrengthLimit = level*0.55; 
                //level*0.495;
        //final double dotStrengthLimit = 0.55;
        
        // PARA, a dot has to be stronger than surrounding by this much
        // final double dotPeakCrit = 1.2;
        // final double dotPeakCrit = 0.35;

        // final double mergeDashesWhenCloser = 2.8/tsLength;
        // final double mergeDotsWhenCloser = 0.8/tsLength;

        final WeightBase wDash = new WeightDash(jDash);
        final WeightBase wDot = new WeightDot(jDot);  // arg ignored really
        //final WeightBase w2 = new WeightTwoDots(jDash);
        //double prevB = Double.MAX_VALUE;
        
        //final int reductionDot = (int) Math.round(80.0*jDotSmall/100);

        // scan for candidate dashes
        TwoDoubles prevD = new TwoDoubles(0.0, Double.MAX_VALUE);
        
        Dash prevDash = null;
        for (int k = 0 + jDash; k < sigSize - jDash; k++) {

            if (prevDash != null && k < prevDash.drop + 2*jDot) {
                // don't consider a dash that would clearly overlap with the previous
                continue;
            }
            
            
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

                try {
                    // index out of bounds can happen?
                    if (dashStrength > dashStrengthLimit) {

                        final Dash candidate = new Dash(kBest, jDot, jDotSmall, jDash, sig, r.a, null);

                        final int i4 = candidate.drop;
                        final int i1 = candidate.rise;
                        final int i2 = i1 + (i4-i1)/3;
                        final int i3 = i2 + (i4-i1)/3 + (i4-i1)%3;

                        final TwoDoubles aa = lsq(sig, i1, i2);
                        final TwoDoubles bb = lsq(sig, i2, i3);
                        final TwoDoubles cc = lsq(sig, i3, i4);

                        // protect against dip in the middle
//                        final double t = w.secondsFromSliceIndex(kBest, framesPerSlice);
//                        if (t >= 81.6 && t <= 82) {
//                            new Info("@@@ %f: %f %f %f", t, aa.a, bb.a, cc.a);
//                        }

                        if (bb.a > middleDipLimit*(aa.a + cc.a)/2) {
                            dashes.put(Integer.valueOf(candidate.k), candidate);
                            prevDash = candidate;
                        }
                    }
                } 
                catch (Exception e) {
                    new Info("cannot create dash, k: %d", kBest);
                }
            }
            prevD = r;
        }

        // check for clustering, merge doublets

        //mergeClusters(dashes, jDot, jDash, mergeDashesWhenCloser);


        // find all dots w/o worrying about dashes

        TwoDoubles prevDot = new TwoDoubles(0.0,  Double.MAX_VALUE);

        final NavigableMap<Integer, ToneBase> dots = new TreeMap<Integer, ToneBase>();

        // TODO: the exact calculation of limits can be refined maybe
        for (int k = 0 + 3*jDotSmall; k < sigSize - 3*jDotSmall; k++) {
            final TwoDoubles r = lsq(sig, k, jDotSmall, wDot);

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
                    
                    final TwoDoubles u1 = lsq(sig, kBest - jDot, jDot, wDot);
                    final TwoDoubles u2 = lsq(sig, kBest + jDot, jDot, wDot);
                    
                    if (u1.b > 0 && u2.b < 0) {
                        dots.put(Integer.valueOf(kBest), new Dot(k, jDot, jDotSmall, sig)); 
                                //new Dot(kBest, kBest - jDot, kBest + jDot));
                    }
                }
            }
            prevDot = r;
        }

        //mergeClusters(dots, jDot, jDash, mergeDotsWhenCloser);

        // remove dashes if there are two competing dots

        // TODO --- THIS IS NOT DONE AT ALL
        final List<Integer> removals = new ArrayList<Integer>();
//        for (Integer key : dashes.navigableKeySet()) {
//
//            final Integer k1 = dots.lowerKey(key);
//            final Integer k2 = dots.higherKey(key);
//
//            if (k1 != null && k2 != null &&
//                    key-jDash < k1 && k1 < key-jDotSmall && key+jDotSmall < k2 && k2 < key+jDash) {
//                //final double t = w.secondsFromSliceIndex(key, framesPerSlice);
//                // new Info("@@@@@@@@@ dash removal, %d, %f - ignored", key, t);
//                removals.add(key);
//            }
//        }

        // remove dots if there is already a dash
        for (Integer key : dots.navigableKeySet()) {
            final Integer k1 = dashes.floorKey(key);
            final Integer k2 = dashes.higherKey(key);

            if (k1 != null && dashes.get(k1).drop >= dots.get(key).rise ||
                    k2 != null && dots.get(key).drop >= dashes.get(k2).rise) {
            //if ((k1 != null && key - k1 < jDashSmall) || (k2 != null && k2 - key < jDashSmall)) {
                // happens a lot so clearly necessary
                //final double t = w.secondsFromSliceIndex(key, framesPerSlice);
                //new Info("@@@@@@@@@ dot removal, %d, %f", key, t);
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
                qCharBegin = dashes.get(key).rise;
            }

            if (prevKey == null) {
                final ToneBase tb = dashes.get(key);
                p = tb instanceof Dash ? p.newNode("-") : p.newNode(".");
                lsqPlotHelper(key, tb, jDotSmall);

            }
            else if (prevKey != null) {
                
                final int toneDistSlices = toneDistSimple(prevKey, key, dashes);
                
                if (toneDistSlices > GerkeDecoder.WORD_SPACE_LIMIT[decoder]/tsLength) {
                    
                    //formatter.add(true, p.text, -1);
                    final int ts =
                            GerkeLib.getFlag(GerkeDecoder.O_TSTAMPS) ?
                            offset + (int) Math.round(key*tsLength*tuMillis/1000) : -1;
                    formatter.add(true, p.text, ts);
                    wpm.chCus += p.nTus;
                    wpm.spCusW += 7;
                    wpm.spTicksW += toneDistSimple(prevKey, key, dashes);
                    
                    p = Node.tree;
                    final ToneBase tb = dashes.get(key);
                    if (tb instanceof Dash) {
                        p = p.newNode("-");
                    }
                    else {
                        p = p.newNode(".");
                    }
                    wpm.chTicks += dashes.get(prevKey).drop - qCharBegin;
                    qCharBegin = dashes.get(key).rise;
                    lsqPlotHelper(key, tb, jDotSmall);
                }
                else if (toneDistSlices > GerkeDecoder.CHAR_SPACE_LIMIT[decoder]/tsLength) {
                    formatter.add(false, p.text, -1);
                    wpm.chCus += p.nTus;
                    wpm.spCusC += 3;
                    wpm.spTicksC += toneDistSimple(prevKey, key, dashes);

                    p = Node.tree;
                    final ToneBase tb = dashes.get(key);
                    if (tb instanceof Dash) {
                        p = p.newNode("-");
                    }
                    else {
                        p = p.newNode(".");
                    }
                    wpm.chTicks += dashes.get(prevKey).drop - qCharBegin;
                    qCharBegin = dashes.get(key).rise;
                    lsqPlotHelper(key, tb, jDotSmall);
                }
                else {
                    final ToneBase tb = dashes.get(key);
                    p = tb instanceof Dash ? p.newNode("-") : p.newNode(".");  
                    lsqPlotHelper(key, tb, jDotSmall);
                }
            }

            prevKey = key;
        }

        if (p != Node.tree) {
            formatter.add(true, p.text, -1);
            formatter.newLine();
            wpm.chCus += p.nTus;
            wpm.chTicks += dashes.get(prevKey).drop - qCharBegin;
        }

        wpm.report();

    }
    
    /**
     * It is trusted that 'tones' is dashes-only or dots-only
     * 
     * TODO, maybe no need for this method
     * 
     * @param tones
     * @param jX
     * @param ticks
     */
    void mergeClusters(
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
    
    
    private int toneDistSimple(
            Integer k1,
            Integer k2,
            NavigableMap<Integer, ToneBase> tones
            ) {
        
        final ToneBase t1 = tones.get(k1);
        final ToneBase t2 = tones.get(k2);
        return t2.rise - t1.drop;
    }
    
    
    
    /**
     * TODO, this method is likely not needed
     * 
     * @param k1
     * @param k2
     * @param tones
     * @param jDash
     * @param jDot
     * @param reductionDot
     * @return
     */
    int toneDist(
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
