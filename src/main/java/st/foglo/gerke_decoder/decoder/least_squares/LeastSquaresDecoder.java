package st.foglo.gerke_decoder.decoder.least_squares;

import java.util.ArrayList;
import java.util.List;
import java.util.NavigableMap;
import java.util.TreeMap;

import st.foglo.gerke_decoder.GerkeLib;
import st.foglo.gerke_decoder.GerkeDecoder;
import st.foglo.gerke_decoder.GerkeDecoder.decoderIndex;
import st.foglo.gerke_decoder.GerkeLib.Debug;
import st.foglo.gerke_decoder.GerkeLib.Info;
import st.foglo.gerke_decoder.decoder.Decoder;
import st.foglo.gerke_decoder.decoder.DecoderBase;
import st.foglo.gerke_decoder.decoder.Node;
import st.foglo.gerke_decoder.decoder.TwoDoubles;
import st.foglo.gerke_decoder.decoder.sliding_line.Dash;
import st.foglo.gerke_decoder.decoder.sliding_line.Dot;
import st.foglo.gerke_decoder.decoder.sliding_line.ToneBase;
import st.foglo.gerke_decoder.decoder.sliding_line.WeightBase;
import st.foglo.gerke_decoder.decoder.sliding_line.WeightDash;
import st.foglo.gerke_decoder.decoder.sliding_line.WeightDot;
import st.foglo.gerke_decoder.format.Formatter;
import st.foglo.gerke_decoder.lib.Compute;
import st.foglo.gerke_decoder.plot.PlotEntries;
import st.foglo.gerke_decoder.wave.Wav;

public final class LeastSquaresDecoder extends DecoderBase implements Decoder {

	final int sigSize;
	
	public LeastSquaresDecoder(
			double tuMillis,
			int framesPerSlice,
			double tsLength,
			int offset,
			Wav w,
			double[] sig,
			PlotEntries plotEntries,
			double[] plotLimits,
			Formatter formatter,
			
			int sigSize,
			double[] cei,
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
				plotLimits,
				formatter,
				cei,
				ceilingMax
				);
		this.sigSize = sigSize;
	};
	
	
	
	
	@Override
	public void execute() {
		
    	final int decoder = decoderIndex.LEAST_SQUARES.ordinal();
    	
        int chCus = 0;
        int chTicks = 0;
        int spCusW = 0;
        int spTicksW = 0;
        int spCusC = 0;
        int spTicksC = 0;

        final NavigableMap<Integer, ToneBase> dashes = new TreeMap<Integer, ToneBase>();

        // in theory, 0.50 .. 0.40 works better
        final int jDotSmall = (int) Math.round(0.40/tsLength);
        final int jDot = jDotSmall;

        final int jDash = (int) Math.round(1.5/tsLength);

        final int jDashSmall = (int) Math.round(1.35/tsLength);

        final double dashStrengthLimit = 0.6;
        
        final double dotStrengthLimit = 0.8;
        //final double dotStrengthLimit = 0.55;
        
        final double dotPeakCrit = 0.3;
        //final double dotPeakCrit = 0.35;

        final double mergeDashesWhenCloser = 2.8/tsLength;
        final double mergeDotsWhenCloser = 0.8/tsLength;

        final WeightBase wDash = new WeightDash(jDash);
        final WeightBase wDot = new WeightDot(jDot);  // arg ignored really
        //final WeightBase w2 = new WeightTwoDots(jDash);
        //double prevB = Double.MAX_VALUE;

        TwoDoubles prevD = new TwoDoubles(0.0, Double.MAX_VALUE);

        for (int k = 0 + jDash; k < sigSize - jDash; k++) {
            final TwoDoubles r = lsq(sig, k, jDash, wDash);

            if (prevD.b >= 0.0 && r.b < 0.0) {

                final int kBest = prevD.b > -r.b ? k : k-1;

                final TwoDoubles aa = lsq(sig, kBest-2*jDot, jDot, wDot);
                final TwoDoubles bb = lsq(sig, kBest, jDot, wDot);
                final TwoDoubles cc = lsq(sig, kBest+2*jDot, jDot, wDot);

                if ((kBest == k ?
                        cei[kBest] - r.a < dashStrengthLimit :
                            cei[kBest] - prevD.a < dashStrengthLimit) &&

                        // PARA - this setting means that a dip in the middle
                        // of the dash will be somewhat protected against.
                        cei[kBest-2*jDot] - aa.a < 0.5 &&
                        cei[kBest] - bb.a < 0.43 &&
                        cei[kBest+2*jDot] - cc.a < 0.5

                        ) {  // logarithmic assumed

                	try {
                		// index out of bounds can happen, hence try
                		// use an average over aa, bb, cc
                		dashes.put(Integer.valueOf(kBest),
                				new Dash(
                						kBest, jDot, jDash, sig,
                						(aa.a + bb.a + cc.a)/3, true));
                	}
                	catch (Exception e) {
                		new Info("cannot create dash, k: %d", kBest);
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

                final int kBest = prevDot.b > -r.b ? k : k-1;
                final double a = prevDot.b > -r.b ? r.a : prevDot.a;

//                final double t = 1+kBest*0.008005;
//                if (t > 276 && t < 276.2) {
//                    new Debug("dot candidate at t: %10f, k: %d, a: %10f", t, k, r.a);
//                    new Debug("jDot: %d, %f", jDot, jDot * 0.008005);
//                    for (int q = -5*jDot; q <= 5*jDot; q += jDot) {
//                        final double dt = q*0.008005;
//
//                        final TwoDoubles x = lsq(sig, k + q, jDot, wDot);
//                        new Debug("t: %f, ampl: %f", t+dt, x.a);
//                    }
//                }

                final TwoDoubles u1 = lsq(sig, kBest - 2*jDot, jDot, wDot);
                final TwoDoubles u2 = lsq(sig, kBest + 2*jDot, jDot, wDot);

                if (cei[kBest] - a < dotStrengthLimit &&

                        a - u1.a > dotPeakCrit &&
                        a - u2.a > dotPeakCrit

                        ) {
                    dots.put(Integer.valueOf(kBest), new Dot(kBest, kBest - jDot, kBest + jDot));
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
            if (prevKey != null && toneDist(prevKey, key, dashes, jDash, jDot) >
            GerkeDecoder.WORD_SPACE_LIMIT[decoder]/tsLength) {
            	
            	//formatter.add(true, p.text, -1);
            	final int ts =
                        GerkeLib.getFlag(GerkeDecoder.O_TSTAMPS) ?
                        offset + (int) Math.round(key*tsLength*tuMillis/1000) : -1;
                formatter.add(true, p.text, ts);
                chCus += p.nTus;
                spCusW += 7;
                spTicksW += lsqToneBegin(key, dashes, jDot) - lsqToneEnd(prevKey, dashes, jDot);
                
                p = Node.tree;
                final ToneBase tb = dashes.get(key);
                if (tb instanceof Dash) {
                	p = p.newNode("-");
                }
                else {
                	p = p.newNode(".");
                }
                chTicks += lsqToneEnd(prevKey, dashes, jDot) - qCharBegin;
                qCharBegin = lsqToneBegin(key, dashes, jDot);
                lsqPlotHelper(key, tb, jDot);
            }
            else if (prevKey != null && toneDist(prevKey, key, dashes, jDash, jDot) > GerkeDecoder.CHAR_SPACE_LIMIT[decoder]/tsLength) {
                formatter.add(false, p.text, -1);
                chCus += p.nTus;
                spCusC += 3;
                
                spTicksC += lsqToneBegin(key, dashes, jDot) - lsqToneEnd(prevKey, dashes, jDot);

//                if (p.text.equals("+")) {
//                    new Info("\n    adding a '+'");
//
//                    ToneBase uu = dashes.get(prevKey);
//                    ToneBase vv = dashes.get(key);
//
//                    new Info("dist: %d, %d, %f", uu.k, vv.k, (vv.k-uu.k - jDash -jDot)*tsLength);
//                }

                p = Node.tree;
                final ToneBase tb = dashes.get(key);
                if (tb instanceof Dash) {
                	p = p.newNode("-");
                }
                else {
                	p = p.newNode(".");
                }
                chTicks += lsqToneEnd(prevKey, dashes, jDot) - qCharBegin;
                qCharBegin = lsqToneBegin(key, dashes, jDot);
                lsqPlotHelper(key, tb, jDot);
            }
            else {
            	final ToneBase tb = dashes.get(key);
            	p = tb instanceof Dash ? p.newNode("-") : p.newNode(".");  
            	lsqPlotHelper(key, tb, jDot);
            }
            prevKey = key;
        }

        if (p != Node.tree) {
            formatter.add(true, p.text, -1);
            formatter.newLine();
            chCus += p.nTus;
            chTicks += lsqToneEnd(prevKey, dashes, jDot) - qCharBegin;
        }

        wpmReport(chCus, chTicks, spCusW, spTicksW, spCusC, spTicksC);

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
                	
                	final ToneBase tone =tones.get(kk); 
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
    
    private int toneDist(
            Integer k1,
            Integer k2,
            NavigableMap<Integer, ToneBase> tones,
            int jDash,
            int jDot) {

        final ToneBase t1 = tones.get(k1);
        final ToneBase t2 = tones.get(k2);

        int reduction = 0;

        final int dotReduction = (int) Math.round(80.0*jDot/100);
        
        if (t1 instanceof Dot) {
        	reduction += dotReduction;
        }
        else if (t1 instanceof Dash) {
        	reduction += ((Dash) t1).drop - ((Dash) t1).k;
        }
        
        if (t2 instanceof Dot) {
        	reduction += dotReduction;
        }
        else if (t2 instanceof Dash) {
        	reduction += ((Dash) t2).k - ((Dash) t2).rise;
        }

        return k2 - k1 - reduction;
    }
    

}
