package st.foglo.gerke_decoder.decoder.sliding_line;

import java.util.NavigableMap;
import java.util.TreeMap;

import st.foglo.gerke_decoder.GerkeDecoder;
import st.foglo.gerke_decoder.GerkeLib;
import st.foglo.gerke_decoder.GerkeDecoder.DecoderIndex;
import st.foglo.gerke_decoder.GerkeLib.Info;
import st.foglo.gerke_decoder.decoder.Dash;
import st.foglo.gerke_decoder.decoder.DecoderBase;
import st.foglo.gerke_decoder.decoder.Dot;
import st.foglo.gerke_decoder.decoder.Node;
import st.foglo.gerke_decoder.decoder.ToneBase;
import st.foglo.gerke_decoder.decoder.TwoDoubles;
import st.foglo.gerke_decoder.format.Formatter;
import st.foglo.gerke_decoder.plot.PlotEntries;
import st.foglo.gerke_decoder.wave.Wav;

public final class SlidingLinePlus extends DecoderBase {
	
	public static final double THRESHOLD = 0.524*0.9;
	
	final int sigSize;
	
	final double level;
	
	/**
	 * Half-width of the sliding line, expressed as nof. slices
	 * PARAMETER 0.40
	 * 
	 * This parameter is quite sensitive!
	 */
	final int halfWidth = (int) Math.round( ((double)8/8) *  0.40/tsLength);

	public SlidingLinePlus(
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
    			ceilingMax,
    			THRESHOLD
				);
		
		this.sigSize = sigSize;
		this.level = level;
	}
	
	private enum States {LOW, M_HIGH, HIGH, M_LOW};

	@Override
	public void execute() {

    	final int decoder = DecoderIndex.LSQ2_PLUS.ordinal();

        final NavigableMap<Integer, ToneBase> tones = new TreeMap<Integer, ToneBase>();

        // in theory, 0.50 .. 0.40 works better .. quite sensitive, TODO, PARAMETER
        final int jDotSmall = (int) Math.round(0.40/tsLength);
        final int jDot = jDotSmall;

        final int jDash = (int) Math.round(1.5/tsLength);

        final WeightBase wDot = new WeightDot(jDot);  // arg ignored really

        
        States state = States.LOW;
        
//        double acc = 0.0;
//        double accMax = 0.0;
//        int maybeCount = 0;
//        double accHigh = 0.0;
        
    	int kRaise = 0;
    	int kDrop = 0;
    	double accSpike = 0.0;
    	int nSpike = 0;
    	double accCrack = 0.0;
    	int nCrack = 0;
    	
    	int maxSpike = 4;      // to be computed
    	int maxCrack = 4;
    	
        for (int k = 0 + jDash; k < sigSize - jDash; k++) {
        	
        	final double thr = threshold(decoder, level, flo[k], cei[k]);
        	final TwoDoubles r = lsq(sig, k, halfWidth, wDot);
        	final boolean high = r.a > thr;
        	final boolean low = !high;
        	
        	//final double tSec = timeSeconds(k);

        	if (state == States.LOW) {
        		if (low) {
        			continue;
        		}
        		else if (high) {
        			kRaise = k;
        			nSpike = 1;
        			accSpike = r.a - thr;
        			state = States.M_HIGH;
        		}
        	}
        	else if (state == States.M_HIGH) {
        		if (low) {
        			// thin spike gets ignored
        			state = States.LOW;
        		}
        		else if (high) {
        			if (k - kRaise > maxSpike) {
        				state = States.HIGH;
        			}
        			nSpike++;
    				accSpike += r.a - thr;
        		}
        	}
        	else if (state == States.HIGH) {
        		if (low) {
        			kDrop = k;
        			state = States.M_LOW;
        		}
        		else if (high) {
        			nSpike++;
    				accSpike += r.a - thr;
        		}
        	}
        	else if (state == States.M_LOW) {
        		if (low) {
        			if (k - kDrop > maxCrack) {
        				state = States.LOW;
						final boolean createDot =
								(kDrop - kRaise)*tsLength <
								GerkeDecoder.DASH_LIMIT[DecoderIndex.LSQ2.ordinal()];

						// possibly reject a dot or dash with too little energy

						final int kMiddle = (int) Math.round((kRaise + kDrop) / 2.0);
						if (createDot) {
							tones.put(Integer.valueOf(kMiddle), new Dot(kMiddle, kRaise, kDrop));
						} else {
							tones.put(Integer.valueOf(kMiddle), new Dash(kMiddle, kRaise, kDrop));
						}
        			}
        			else {
        				continue;
        			}
        		}
        		else if (high) {
        			// ignore a crack
        			state = States.HIGH;
        			nSpike++;
    				accSpike += r.a - thr;
        		}
        	}
        	
        	

//
//        	else if (!isHigh && high) {
//        		highBegin = k;
//        		acc = r.a - thr;
//        		accMax = cei[k] - thr;
//        		isHigh = true;
//        	}
//        	else if (isHigh && high) {
//        		acc += r.a - thr;
//        		accMax += cei[k] - thr;
//        	}
//        	else if (isHigh && !high && ((double)(framesPerSlice*(k - highBegin)))/w.frameRate < 0.10*tuMillis/1000) {  // PARA PARA
//        		new Info("ignoring very thin dot: %d, %f", k, timeSeconds(k));
//        		isHigh = false;
//        	}
//        	else if (isHigh && !high && acc < 0.03*accMax) {  // PARA PARA
//        		// ignore very weak dot
//        		new Info("ignoring very weak dot: %d, %f", k, timeSeconds(k));
//        		isHigh = false;
//        	}
//        	else if (isHigh && !high &&
//        			(k - highBegin)*tsLength < GerkeDecoder.DASH_LIMIT[DecoderIndex.LSQ2.ordinal()]) {
//        		// create Dot
//        		final int kMiddle = (int) Math.round((highBegin + k)/2.0);
//        		tones.put(Integer.valueOf(kMiddle),
//        				new Dot(kMiddle, highBegin, k));
//
//        		isHigh = false;
//        	}
//        	else if (isHigh && !high) {
//        		// create Dash
//        		final int kMiddle = (int) Math.round((highBegin + k)/2.0);
//        		tones.put(Integer.valueOf(kMiddle),
//        				new Dash(kMiddle, highBegin, k));
//
//        		isHigh = false;
//        	}
//        	else if (!isHigh && !high) {
//        		if (tSec > 458 && tSec < 458.7) { new Info("_   %d, %f", k, tSec); }
//        	}
        }

        Node p = Node.tree;
        int qCharBegin = -999999;
        Integer prevKey = null;
        for (Integer key : tones.navigableKeySet()) {

        	if (prevKey == null) {
        		qCharBegin = lsqToneBegin(key, tones, jDot);
        	}
        	
			if (prevKey == null) {
				final ToneBase tb = tones.get(key);
				p = tb instanceof Dash ? p.newNode("-") : p.newNode(".");
				lsqPlotHelper(key, tb, jDot);
				
			} else if (prevKey != null) {
				
	            final ToneBase t1 = tones.get(prevKey);
	            final ToneBase t2 = tones.get(key);
	        	final int toneDistSlices = t2.rise - t1.drop;
	        	
				if (toneDistSlices > GerkeDecoder.WORD_SPACE_LIMIT[decoder] / tsLength) {
					final int ts = GerkeLib.getFlag(GerkeDecoder.O_TSTAMPS)
							? offset + (int) Math.round(key * tsLength * tuMillis / 1000)
							: -1;
					formatter.add(true, p.text, ts);
					wpm.chCus += p.nTus;
					wpm.spCusW += 7;
					wpm.spTicksW += lsqToneBegin(key, tones, jDot) - lsqToneEnd(prevKey, tones, jDot);

					p = Node.tree;
					final ToneBase tb = tones.get(key);
					if (tb instanceof Dash) {
						p = p.newNode("-");
					} else {
						p = p.newNode(".");
					}
					wpm.chTicks += lsqToneEnd(prevKey, tones, jDot) - qCharBegin;
					qCharBegin = lsqToneBegin(key, tones, jDot);
					lsqPlotHelper(key, tb, jDot);
					
				} else if (toneDistSlices > GerkeDecoder.CHAR_SPACE_LIMIT[decoder] / tsLength) {
					formatter.add(false, p.text, -1);
					wpm.chCus += p.nTus;
					wpm.spCusC += 3;

					wpm.spTicksC += lsqToneBegin(key, tones, jDot) - lsqToneEnd(prevKey, tones, jDot);

					p = Node.tree;
					final ToneBase tb = tones.get(key);
					if (tb instanceof Dash) {
						p = p.newNode("-");
					} else {
						p = p.newNode(".");
					}
					wpm.chTicks += lsqToneEnd(prevKey, tones, jDot) - qCharBegin;
					qCharBegin = lsqToneBegin(key, tones, jDot);
					lsqPlotHelper(key, tb, jDot);
				} else {
					final ToneBase tb = tones.get(key);
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
            wpm.chTicks += lsqToneEnd(prevKey, tones, jDot) - qCharBegin;
        }

        wpm.report();

	}

}
