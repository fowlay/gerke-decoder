package st.foglo.gerke_decoder.decoder;

import java.util.NavigableMap;

import st.foglo.gerke_decoder.GerkeDecoder;
import st.foglo.gerke_decoder.GerkeLib.Death;
import st.foglo.gerke_decoder.GerkeLib.Info;
import st.foglo.gerke_decoder.decoder.sliding_line.Dash;
import st.foglo.gerke_decoder.decoder.sliding_line.Dot;
import st.foglo.gerke_decoder.decoder.sliding_line.ToneBase;
import st.foglo.gerke_decoder.decoder.sliding_line.WeightBase;
import st.foglo.gerke_decoder.format.Formatter;
import st.foglo.gerke_decoder.plot.PlotEntries;
import st.foglo.gerke_decoder.wave.Wav;

public abstract class DecoderBase implements Decoder {

	public final double tuMillis;
	public final int framesPerSlice;
	public final double tsLength;
	public final int offset;
	public final Wav w;
	
	public final double[] sig;
	
	public final PlotEntries plotEntries;
	public final double[] plotLimits;
	
	public final Formatter formatter;

	protected DecoderBase(
			double tuMillis,
			int framesPerSlice,
			double tsLength,
			int offset,
			Wav w,
			double[] sig,
			PlotEntries plotEntries,
			double[] plotLimits,
			Formatter formatter) {

		this.tuMillis = tuMillis;
		this.framesPerSlice = framesPerSlice;
		this.tsLength = tsLength;
		this.offset = offset;
		this.w = w;
		this.sig = sig;
		this.plotEntries = plotEntries;
		this.plotLimits = plotLimits;
		this.formatter = formatter;
	}
	
    /**
     * Determines threshold based on decoder and amplitude mapping.
     */
    public static double threshold(
            int decoder,
            int ampMap,
            double level,
            double levelLog,
            double floor,
            double ceiling) {

        if (ampMap == 3) {
        	// logarithmic mapping, floor is ignored
            return ceiling + GerkeDecoder.THRESHOLD_BY_LOG[decoder] + levelLog;
        }
        else if (ampMap == 2 || ampMap == 1) {
            return floor + level*GerkeDecoder.THRESHOLD[decoder]*(ceiling - floor);
        }
        else {
            new Death("invalid amplitude mapping: %d", ampMap);
            return 0.0;
        }
    }
    
    public static TwoDoubles lsq(double[] sig, int k, int jMax, WeightBase weight) {

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
    
    public static int lsqToneBegin(Integer key, NavigableMap<Integer, ToneBase> tones, int jDot) {
    	ToneBase tone = tones.get(key);
    	if (tone instanceof Dash) {
    		return ((Dash)tone).rise;
    	}
    	else {
    		final int dotReduction = (int) Math.round(70.0*jDot/100);
    		return tone.k - dotReduction;
    	}	
    }
    
    public static int lsqToneEnd(Integer key, NavigableMap<Integer, ToneBase> tones, int jDot) {
    	ToneBase tone = tones.get(key);
    	if (tone instanceof Dash) {
    		return ((Dash)tone).drop;
    	}
    	else {
    		final int dotReduction = (int) Math.round(70.0*jDot/100);
    		return tone.k + dotReduction;
    	}	
    }
    
    public static int toneDist2(
            Integer k1,
            Integer k2,
            NavigableMap<Integer, ToneBase> tones,
            int jDash,
            int jDot) {

        final ToneBase t1 = tones.get(k1);
        final ToneBase t2 = tones.get(k2);

        
        int reduction = 0;
        
        if (t1 instanceof Dot) {
        	reduction += ((Dot) t1).drop - ((Dot) t1).k;
        }
        else if (t1 instanceof Dash) {
        	reduction += ((Dash) t1).drop - ((Dash) t1).k;
        }
        
        
        if (t2 instanceof Dot) {
        	reduction += ((Dot) t2).k - ((Dot) t2).rise;
        }
        else if (t2 instanceof Dash) {
        	reduction += ((Dash) t2).k - ((Dash) t2).rise;
        }

        return k2 - k1 - reduction;
    }
    
    protected static void lsqPlotHelper(PlotEntries plotEntries, double[] plotLimits, Integer key, ToneBase tb,
    		int jDot, int framesPerSlice, int frameRate, int offsetFrames, double ceilingMax) {
    	final int dotReduction = (int) Math.round(80.0*jDot/100);
    	if (plotEntries != null) {
    		if (tb instanceof Dot) {
    			final int kRise = key - dotReduction;
    			final int kDrop = key + dotReduction;
    			final double secRise1 = timeSeconds(kRise, framesPerSlice, frameRate, offsetFrames);
    			final double secRise2 = timeSeconds(kRise+1, framesPerSlice, frameRate, offsetFrames);
    			final double secDrop1 = timeSeconds(kDrop, framesPerSlice, frameRate, offsetFrames);
    			final double secDrop2 = timeSeconds(kDrop+1, framesPerSlice, frameRate, offsetFrames);
    			if (plotLimits[0] < secRise1 && secDrop2 < plotLimits[1]) {
    				plotEntries.addDecoded(secRise1, ceilingMax/20);
    				plotEntries.addDecoded(secRise2, 2*ceilingMax/20);
    				plotEntries.addDecoded(secDrop1, 2*ceilingMax/20);
    				plotEntries.addDecoded(secDrop2, ceilingMax/20);
    			}
    		}
        	else if (tb instanceof Dash) {
    			final int kRise = ((Dash) tb).rise;
    			final int kDrop = ((Dash) tb).drop;
    			final double secRise1 = timeSeconds(kRise, framesPerSlice, frameRate, offsetFrames);
    			final double secRise2 = timeSeconds(kRise+1, framesPerSlice, frameRate, offsetFrames);
    			final double secDrop1 = timeSeconds(kDrop, framesPerSlice, frameRate, offsetFrames);
    			final double secDrop2 = timeSeconds(kDrop+1, framesPerSlice, frameRate, offsetFrames);
    			if (plotLimits[0] < secRise1 && secDrop2 < plotLimits[1]) {
    				plotEntries.addDecoded(secRise1, ceilingMax/20);
    				plotEntries.addDecoded(secRise2, 2*ceilingMax/20);
    				plotEntries.addDecoded(secDrop1, 2*ceilingMax/20);
    				plotEntries.addDecoded(secDrop2, ceilingMax/20);
    			}
        	}
    	}
    }
    
    private static double timeSeconds(int q, int framesPerSlice, int frameRate, int offsetFrames) {
        return (((double) q)*framesPerSlice + offsetFrames)/frameRate;
    }
    
    /**
     * Report WPM estimates
     * 
     * @param chCus             nominal nof. TUs in character
     * @param chTicks           actual nof. ticks in character
     * @param spCusW            nominal nof. TUs in word spaces (always increment by 7)
     * @param spTicksW          actual nof. ticks in word spaces
     * @param spCusC            nominal nof. TUs in char spaces (always increment by 3)
     * @param spTicksC          actual nof. ticks in char spaces
     * @param tuMillis
     * @param tsLength
     */
    protected static void wpmReport(
            int chCus,
            int chTicks,
            int spCusW,
            int spTicksW,
            int spCusC,
            int spTicksC,
            double tuMillis,
            double tsLength) {

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
