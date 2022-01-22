package st.foglo.gerke_decoder.decoder;

import java.util.NavigableMap;

import st.foglo.gerke_decoder.GerkeDecoder;
import st.foglo.gerke_decoder.GerkeLib;
import st.foglo.gerke_decoder.GerkeDecoder.DetectorIndex;
import st.foglo.gerke_decoder.GerkeDecoder.HiddenOpts;
import st.foglo.gerke_decoder.GerkeLib.Death;
import st.foglo.gerke_decoder.GerkeLib.Debug;
import st.foglo.gerke_decoder.GerkeLib.Info;
import st.foglo.gerke_decoder.GerkeLib.Trace;
import st.foglo.gerke_decoder.decoder.sliding_line.WeightBase;
import st.foglo.gerke_decoder.format.Formatter;
import st.foglo.gerke_decoder.lib.Compute;
import st.foglo.gerke_decoder.plot.PlotEntries;
import st.foglo.gerke_decoder.wave.Wav;

public abstract class DecoderBase implements Decoder {

	protected double tuMillis;
	protected int framesPerSlice;
	protected double tsLength;
	protected int offset;
	protected Wav w;
	
	protected double[] sig;
	
	protected PlotEntries plotEntries;
	
	protected Formatter formatter;
	
	protected double[] cei;
	protected double[] flo;
	protected double ceilingMax;
	protected final Wpm wpm = new Wpm();
	
	public final double threshold;
	
	public class Wpm {
		public int chCus = 0;       // character
		public int chTicks = 0;
		
		public int spCusW = 0;      // word space
		public int spTicksW = 0;
		
		public int spCusC = 0;      // char space
		public int spTicksC = 0;
		
	    /**
	     * Report WPM estimates
	     * 
	     * @param chCus             nominal nof. TUs in character
	     * @param chTicks           actual nof. ticks in character
	     * @param spCusW            nominal nof. TUs in word spaces (always increment by 7)
	     * @param spTicksW          actual nof. ticks in word spaces
	     * @param spCusC            nominal nof. TUs in char spaces (always increment by 3)
	     * @param spTicksC          actual nof. ticks in char spaces
	     */
	    public void report() {

	        if (chTicks > 0) {
	            new Info("within-characters WPM rating: %.1f",
	                    1200*chCus/(chTicks*tuMillis*tsLength));
	        }
	        if (spCusC > 0) {
	            new Info("expansion of inter-char spaces: %.3f",
	                    (spTicksC*tsLength)/spCusC);
	        }
	        if (spCusW > 0) {
	            new Info("expansion of inter-word spaces: %.3f",
	                    (spTicksW*tsLength)/spCusW);
	        }
	        if (spTicksW + spTicksC + chTicks > 0) {
	            new Info("effective WPM: %.1f",
	                    1200*(spCusW + spCusC + chCus)/((spTicksW + spTicksC + chTicks)*tuMillis*tsLength));
	        }
	    }
	}
	
	protected DecoderBase(
			double tuMillis,
			int framesPerSlice,
			double tsLength,
			int offset,
			Wav w,
			double[] sig,
			PlotEntries plotEntries,
			Formatter formatter,
			double[] cei,
			double[] flo,
			double ceilingMax,
			double threshold) {

		this.tuMillis = tuMillis;
		this.framesPerSlice = framesPerSlice;
		this.tsLength = tsLength;
		this.offset = offset;
		this.w = w;
		this.sig = sig;
		this.plotEntries = plotEntries;
		this.formatter = formatter;
		this.cei = cei;
		this.flo = flo;
		this.ceilingMax = ceilingMax;
		
		this.threshold = threshold;
	}
	
	
	/**
	 * Returns the index of the suitable detector for this decoder. This method is
	 * static since we need the detector before the decoder is instantiated.
	 * @return
	 */
	public static DetectorIndex getDetector(int decoder) {
		if (decoder == 5 || decoder == 6) {
			return DetectorIndex.ADAPTIVE_DETECTOR;
		}
		else if (decoder == 4 || decoder == 3 || decoder == 2 || decoder == 1) {
			return DetectorIndex.BASIC_DETECTOR;
		}
		else {
			new Death("no such decoder: %d", decoder);
			throw new RuntimeException();
		}
	}
	
	
    /**
     * Determines threshold based on decoder and amplitude mapping.
     */
    protected double threshold(
            int decoder,
            double level,
            double floor,
            double ceiling) {

        return floor + level*threshold*(ceiling - floor);
    }
    
    protected TwoDoubles lsq(
    		double[] sig,
    		int k,
    		int jMax,
    		WeightBase weight
    		) {

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
    
    protected int lsqToneBegin(Integer key, NavigableMap<Integer, ToneBase> tones, int jDot) {
    	ToneBase tone = tones.get(key);
    	if (tone instanceof Dash) {
    		return ((Dash)tone).rise;
    	}
    	else {
    		final int dotReduction = (int) Math.round(70.0*jDot/100);
    		return tone.k - dotReduction;
    	}	
    }
    
    protected int lsqToneEnd(Integer key, NavigableMap<Integer, ToneBase> tones, int jDot) {
    	ToneBase tone = tones.get(key);
    	if (tone instanceof Dash) {
    		return ((Dash)tone).drop;
    	}
    	else {
    		final int dotReduction = (int) Math.round(70.0*jDot/100);
    		return tone.k + dotReduction;
    	}	
    }

    protected void lsqPlotHelper(Integer key, ToneBase tb,
    		int jDot) {
    	final int dotReduction = (int) Math.round(80.0*jDot/100);
    	if (plotEntries != null) {
    		if (tb instanceof Dot) {
    			final int kRise = key - dotReduction;
    			final int kDrop = key + dotReduction;
    			final double secRise1 = timeSeconds(kRise);
    			final double secRise2 = timeSeconds(kRise+1);
    			final double secDrop1 = timeSeconds(kDrop);
    			final double secDrop2 = timeSeconds(kDrop+1);
    			if (plotEntries.plotBegin < secRise1 && secDrop2 < plotEntries.plotEnd) {
    				plotEntries.addDecoded(secRise1, ceilingMax/20);
    				plotEntries.addDecoded(secRise2, 2*ceilingMax/20);
    				plotEntries.addDecoded(secDrop1, 2*ceilingMax/20);
    				plotEntries.addDecoded(secDrop2, ceilingMax/20);
    			}
    		}
        	else if (tb instanceof Dash) {
    			final int kRise = ((Dash) tb).rise;
    			final int kDrop = ((Dash) tb).drop;
    			final double secRise1 = timeSeconds(kRise);
    			final double secRise2 = timeSeconds(kRise+1);
    			final double secDrop1 = timeSeconds(kDrop);
    			final double secDrop2 = timeSeconds(kDrop+1);
    			if (plotEntries.plotBegin < secRise1 && secDrop2 < plotEntries.plotEnd) {
    				plotEntries.addDecoded(secRise1, ceilingMax/20);
    				plotEntries.addDecoded(secRise2, 2*ceilingMax/20);
    				plotEntries.addDecoded(secDrop1, 2*ceilingMax/20);
    				plotEntries.addDecoded(secDrop2, ceilingMax/20);
    			}
        	}
    	}
    }
    
    /**
     * Convert from slice index to seconds.
     * @param q
     * @return
     */
    protected double timeSeconds(int q) {
        return (((double) q)*framesPerSlice + w.offsetFrames)/w.frameRate;
    }
    
    protected  Trans[] findTransitions(
    		int nofSlices,
    		int decoder,
    		double level,
    		double[] flo) {
    	
    	final int charSpaceLimit =
    			(int) Math.round(GerkeDecoder.CHAR_SPACE_LIMIT[decoder]*tuMillis*w.frameRate/(1000*framesPerSlice));   // PARAMETER
    	
    	final int twoDashLimit = (int) Math.round(GerkeDecoder.TWO_DASH_LIMIT*tuMillis*w.frameRate/(1000*framesPerSlice));     // PARAMETER
    	
    	
    	 // ============== identify transitions
        // usage depends on decoder method

        final Trans[] tr = new Trans[nofSlices];
        int transIndex = 0;
        boolean tone = false;
        double dipAcc = 0.0;
        double spikeAcc = 0.0;
        double thresholdMax = -1.0;
        // double ceilingMax = -1.0; .. already done
        new Debug("thresholdMax is: %e", thresholdMax);



        for (int q = 0; true; q++) {
            if (w.wav.length - q*framesPerSlice < framesPerSlice) {
                break;
            }

            final double threshold = threshold(decoder, level, flo[q], cei[q]);
            thresholdMax = Compute.dMax(threshold, thresholdMax);

            final boolean newTone = sig[q] > threshold;

            if (newTone && !tone) {
                // raise
                if (transIndex > 0 && q - tr[transIndex-1].q <= charSpaceLimit) {
                    tr[transIndex] = new Trans(q, true, dipAcc, cei[q], flo[q]);
                }
                else {
                    tr[transIndex] = new Trans(q, true, cei[q], flo[q]);
                }
                transIndex++;
                tone = true;
                spikeAcc = Compute.squared(threshold - sig[q]);
            }
            else if (!newTone && tone) {
                // fall
                tr[transIndex] = new Trans(q, false, spikeAcc, cei[q], flo[q]);
                transIndex++;
                tone = false;
                dipAcc = Compute.squared(threshold - sig[q]);
            }
            else if (!tone) {
                dipAcc += Compute.squared(threshold - sig[q]);
            }
            else if (tone) {
                spikeAcc += Compute.squared(threshold - sig[q]);
            }
        }

        new Debug("transIndex: %d", transIndex);

        // Eliminate small dips

        final double silentTu = (1.0/tsLength)*(0.5*thresholdMax)*(0.5*thresholdMax);
        new Debug("thresholdMax is: %e", thresholdMax);
        new Debug("tsLength is: %e", tsLength);
        new Debug("silentTu is: %e", silentTu);
        final double dipLimit = GerkeLib.getDoubleOptMulti(GerkeDecoder.O_HIDDEN)[HiddenOpts.DIP.ordinal()];
        final int veryShortDip = (int) Math.round(0.2/tsLength);  // PARAMETER 0.2

        for (int t = 1; t < transIndex; t++) {
            if (transIndex > 0 &&
                    tr[t].rise &&
                    tr[t].dipAcc != -1.0 &&
                    (tr[t].dipAcc < dipLimit*silentTu || tr[t].q - tr[t-1].q <= veryShortDip)) {
                new Trace("dip at: %d, width: %d, mass: %f, fraction: %f",
                        t,
                        tr[t].q - tr[t-1].q,
                        tr[t].dipAcc,
                        tr[t].dipAcc/silentTu);
                if (t+1 < transIndex) {
                    // preserve accumulated spike value
                    tr[t+1] =
                            new Trans(tr[t+1].q,
                                    false,
                                    tr[t+1].spikeAcc + tr[t-1].spikeAcc,
                                    tr[t].ceiling,
                                    tr[t].floor
                                    );
                }
                tr[t-1] = null;
                tr[t] = null;
            }
            else if (transIndex > 0 &&
                    tr[t].rise &&
                    tr[t].dipAcc != -1.0 && t % 200 == 0) {
                new Trace("dip at: %d, width: %d, mass: %e, limit: %e",
                        t,
                        tr[t].q - tr[t-1].q,
                        tr[t].dipAcc,
                        dipLimit*silentTu);
            }
        }

        transIndex = removeHoles(tr, transIndex);

        // check if potential spikes exist
        boolean hasSpikes = false;
        for (int t = 0; t < transIndex; t++) {
            if (tr[t].spikeAcc > 0) {
                hasSpikes = true;
                break;
            }
        }

        // remove spikes

        final int veryShortSpike = (int) Math.round(0.2/tsLength);  // PARAMETER 0.2
        if (hasSpikes) {
            final double spikeLimit = GerkeLib.getDoubleOptMulti(GerkeDecoder.O_HIDDEN)[HiddenOpts.SPIKE.ordinal()];
            for (int t = 1; t < transIndex; t++) {
                if (!tr[t].rise && tr[t].spikeAcc != -1.0 &&
                        (tr[t].spikeAcc < spikeLimit*silentTu || tr[t].q - tr[t-1].q <= veryShortSpike)) {
                    tr[t-1] = null;
                    tr[t] = null;
                }
            }
        }
        transIndex = removeHoles(tr, transIndex);

        // break up very long dashes

        final boolean breakLongDash =
                GerkeLib.getIntOptMulti(GerkeDecoder.O_HIDDEN)
                [HiddenOpts.BREAK_LONG_DASH.ordinal()] == 1;

        if (breakLongDash) {
            // TODO, refactor .. break off the first dash, then reconsider the rest
            for (int t = 1; t < transIndex; ) {
                if (tr[t-1].rise && !tr[t].rise && tr[t].q - tr[t-1].q > twoDashLimit) {
                    // break up a very long dash, make room for 2 more events
                    for (int tt = transIndex-1; tt > t; tt--) {
                        tr[tt+2] = tr[tt];
                    }
                    transIndex += 2;
                    // fair split
                    Trans big = tr[t];
                    int dashSize = big.q - tr[t-1].q;
                    int q1 = tr[t-1].q + dashSize/2 - (int) Math.round(0.5/tsLength);
                    int q2 = tr[t-1].q + dashSize/2 + (int) Math.round(0.5/tsLength);
                    int q3 = big.q;
                    double acc = big.spikeAcc;
                    double ceiling = tr[t-1].ceiling;
                    double floor = tr[t-1].floor;
                    tr[t] = new Trans(q1, false, acc/2, ceiling, floor);
                    tr[t+1] = new Trans(q2, true, acc/2, ceiling, floor);
                    tr[t+2] = new Trans(q3, false, acc/2, ceiling, floor);
                    t +=3;
                }
                else {
                    t++;
                }
            }
        }

        ///////////////////////
        // transition list is ready
        ///////////////////////

        if (transIndex == 0) {
            new Death("no signal detected");
        }
        else if (transIndex == 1) {
            new Death("no code detected");
        }
    	
        Trans[] result = new Trans[transIndex];
        for (int i = 0; i < transIndex; i++) {
        	result[i] = tr[i];
        }
    	return result;
    }
    
    /**
     * Remove null elements from the given array. The returned value
     * is the new active size.
     *
     * @param trans
     * @param activeSize
     * @return
     */
    protected int removeHoles(Trans[] trans, int activeSize) {
        int k = 0;
        for (int i = 0; i < activeSize; i++) {
            if (trans[i] != null) {
                trans[k] = trans[i];
                k++;
            }
        }
        return k;
    }
}

