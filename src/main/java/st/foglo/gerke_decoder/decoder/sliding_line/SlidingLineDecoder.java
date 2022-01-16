package st.foglo.gerke_decoder.decoder.sliding_line;

import java.util.NavigableMap;
import java.util.TreeMap;

import st.foglo.gerke_decoder.GerkeLib;
import st.foglo.gerke_decoder.GerkeLib.Info;
import st.foglo.gerke_decoder.GerkeDecoder;
import st.foglo.gerke_decoder.GerkeDecoder.DecoderIndex;
import st.foglo.gerke_decoder.decoder.DecoderBase;
import st.foglo.gerke_decoder.decoder.Node;
import st.foglo.gerke_decoder.decoder.TwoDoubles;
import st.foglo.gerke_decoder.format.Formatter;
import st.foglo.gerke_decoder.plot.PlotEntries;
import st.foglo.gerke_decoder.wave.Wav;

public final class SlidingLineDecoder extends DecoderBase {
	
	final int sigSize;
	
	final double level;
	
	/**
	 * Half-width of the sliding line, expressed as nof. slices
	 * PARAMETER 0.40
	 * 
	 * This parameter is quite sensitive!
	 */
	final int halfWidth = (int) Math.round( ((double)8/8) *  0.40/tsLength);

	public SlidingLineDecoder(
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
			double[] flo,
			double level,
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
    			flo,
    			ceilingMax
				);
		
		this.sigSize = sigSize;
		
		this.level = level;
		
		new Info("sliding line half-width (slices): %d", halfWidth);
		
	}

	@Override
	public void execute() {

    	final int decoder = DecoderIndex.LSQ2.ordinal();

        int chCus = 0;
        int chTicks = 0;
        int spCusW = 0;
        int spTicksW = 0;
        int spCusC = 0;
        int spTicksC = 0;

        final NavigableMap<Integer, ToneBase> dashes = new TreeMap<Integer, ToneBase>();

        // in theory, 0.50 .. 0.40 works better .. quite sensitive, TODO, PARAMETER
        final int jDotSmall = (int) Math.round(0.40/tsLength);
        final int jDot = jDotSmall;

        final int jDash = (int) Math.round(1.5/tsLength);

        final WeightBase wDot = new WeightDot(jDot);  // arg ignored really

        int highBegin = -999;
        boolean isHigh = false;
        double acc = 0.0;
        double accMax = 0.0;
        for (int k = 0 + jDash; k < sigSize - jDash; k++) {
        	
        	final double thr = threshold(decoder, level, flo[k], cei[k]);
        	
        	final TwoDoubles r = lsq(sig, k, halfWidth, wDot);

        	final boolean high = r.a > thr;
        	
        	final double tSec = timeSeconds(k);

        	if (!isHigh && !high) {
        		continue;
        	}
        	else if (!isHigh && high) {
        		highBegin = k;
        		acc = r.a - thr;
        		accMax = cei[k] - thr;
        		isHigh = true;
        	}
        	else if (isHigh && high) {
        		acc += r.a - thr;
        		accMax += cei[k] - thr;
        	}
        	else if (isHigh && !high && ((double)(framesPerSlice*(k - highBegin)))/w.frameRate < 0.10*tuMillis/1000) {  // PARA PARA
        		new Info("ignoring very thin dot: %d, %f", k, timeSeconds(k));
        		isHigh = false;
        	}
        	else if (isHigh && !high && acc < 0.03*accMax) {  // PARA PARA
        		// ignore very weak dot
        		new Info("ignoring very weak dot: %d, %f", k, timeSeconds(k));
        		isHigh = false;
        	}
        	else if (isHigh && !high &&
        			(k - highBegin)*tsLength < GerkeDecoder.DASH_LIMIT[DecoderIndex.LSQ2.ordinal()]) {
        		// create Dot
        		final int kMiddle = (int) Math.round((highBegin + k)/2.0);
        		dashes.put(Integer.valueOf(kMiddle),
        				new Dot(kMiddle, highBegin, k));

        		isHigh = false;
        	}
        	else if (isHigh && !high) {
        		// create Dash
        		final int kMiddle = (int) Math.round((highBegin + k)/2.0);
        		dashes.put(Integer.valueOf(kMiddle),
        				new Dash(kMiddle, highBegin, k, r.a));
        		// TODO r.a above maybe not used

        		isHigh = false;
        	}
        	else if (!isHigh && !high) {
        		if (tSec > 458 && tSec < 458.7) { new Info("_   %d, %f", k, tSec); }
        	}
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
				
			} else if (prevKey != null) {
				
	            final ToneBase t1 = dashes.get(prevKey);
	            final ToneBase t2 = dashes.get(key);
	        	final int toneDistSlices = t2.rise - t1.drop;
	        	
				if (toneDistSlices > GerkeDecoder.WORD_SPACE_LIMIT[decoder] / tsLength) {
					final int ts = GerkeLib.getFlag(GerkeDecoder.O_TSTAMPS)
							? offset + (int) Math.round(key * tsLength * tuMillis / 1000)
							: -1;
					formatter.add(true, p.text, ts);
					chCus += p.nTus;
					spCusW += 7;
					spTicksW += lsqToneBegin(key, dashes, jDot) - lsqToneEnd(prevKey, dashes, jDot);

					p = Node.tree;
					final ToneBase tb = dashes.get(key);
					if (tb instanceof Dash) {
						p = p.newNode("-");
					} else {
						p = p.newNode(".");
					}
					chTicks += lsqToneEnd(prevKey, dashes, jDot) - qCharBegin;
					qCharBegin = lsqToneBegin(key, dashes, jDot);
					lsqPlotHelper(key, tb, jDot);
					
				} else if (toneDistSlices > GerkeDecoder.CHAR_SPACE_LIMIT[decoder] / tsLength) {
					formatter.add(false, p.text, -1);
					chCus += p.nTus;
					spCusC += 3;

					spTicksC += lsqToneBegin(key, dashes, jDot) - lsqToneEnd(prevKey, dashes, jDot);

					p = Node.tree;
					final ToneBase tb = dashes.get(key);
					if (tb instanceof Dash) {
						p = p.newNode("-");
					} else {
						p = p.newNode(".");
					}
					chTicks += lsqToneEnd(prevKey, dashes, jDot) - qCharBegin;
					qCharBegin = lsqToneBegin(key, dashes, jDot);
					lsqPlotHelper(key, tb, jDot);
				} else {
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
            chCus += p.nTus;
            chTicks += lsqToneEnd(prevKey, dashes, jDot) - qCharBegin;
        }

        wpmReport(chCus, chTicks, spCusW, spTicksW, spCusC, spTicksC);
  
		
	}
	
//    private int toneDist2(
//            Integer k1,
//            Integer k2,
//            NavigableMap<Integer, ToneBase> tones,
//            int jDash,
//            int jDot) {
//
//        final ToneBase t1 = tones.get(k1);
//        final ToneBase t2 = tones.get(k2);
//
//        
//        int reduction = 0;
//        
//        if (t1 instanceof Dot) {
//        	reduction += ((Dot) t1).drop - ((Dot) t1).k;
//        }
//        else if (t1 instanceof Dash) {
//        	reduction += ((Dash) t1).drop - ((Dash) t1).k;
//        }
//        
//        
//        if (t2 instanceof Dot) {
//        	reduction += ((Dot) t2).k - ((Dot) t2).rise;
//        }
//        else if (t2 instanceof Dash) {
//        	reduction += ((Dash) t2).k - ((Dash) t2).rise;
//        }
//
//        return k2 - k1 - reduction;
//    }
}
