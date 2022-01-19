package st.foglo.gerke_decoder.decoder.sliding_line;

import java.util.NavigableMap;
import java.util.TreeMap;

import st.foglo.gerke_decoder.GerkeDecoder;
import st.foglo.gerke_decoder.GerkeLib;
import st.foglo.gerke_decoder.GerkeDecoder.DecoderIndex;
import st.foglo.gerke_decoder.GerkeLib.Debug;
import st.foglo.gerke_decoder.decoder.Dash;
import st.foglo.gerke_decoder.decoder.DecoderBase;
import st.foglo.gerke_decoder.decoder.Dot;
import st.foglo.gerke_decoder.decoder.Node;
import st.foglo.gerke_decoder.decoder.ToneBase;
import st.foglo.gerke_decoder.decoder.TwoDoubles;
import st.foglo.gerke_decoder.format.Formatter;
import st.foglo.gerke_decoder.lib.Compute;
import st.foglo.gerke_decoder.plot.PlotEntries;
import st.foglo.gerke_decoder.wave.Wav;

public final class SlidingLinePlus extends DecoderBase {
	
	/**
	 * Unit is TU.
	 */
	public static final double TS_LENGTH = 0.06;
	
	public static final double THRESHOLD = 0.55; // 0.524*0.9;
	
	/**
	 * If the amplitude at a crack doesn't drop more than this,
	 * the crack can be ignored.
	 */
	private static final double crackDipLimit = 0.5;
	
	
	/**
	 * If the relative amplitude of a spike is not more than this
	 * then it can be ignored.
	 */
	private static final double spikeLimit = 0.548;
	
	/**
	 * Maximum width of a spike; unit is TU
	 */
	private static final double spikeWidth = 0.35;

	/**
	 * Maximum width of a crack; unit is TU
	 */
	private static final double crackWidth = 0.33;
	
	final int sigSize;
	
	final double level;
	
	/**
	 * Half-width of the sliding line, expressed as nof. slices
	 * PARAMETER 0.40
	 * 
	 * This parameter is quite sensitive!
	 */
	final int halfWidth = (int) Math.round( ((double)7/8) *  0.40/tsLength);

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

        // in theory: 0.5, whereas 0.40 works better .. quite sensitive
        final int jDot = (int) Math.round(0.40/tsLength);

        final int jDash = (int) Math.round(1.5/tsLength);

        final WeightBase wDot = new WeightDot(jDot);  // arg ignored really

        States state = States.LOW;

    	int kRaise = 0;
    	int kDrop = 0;
    	double accSpike = 0.0;        // accumulate squared actual signal
    	double nominalSpike = 0.0;    // accumulate squared possible full signal
    	
    	double accCrack = 0.0;        // accumulate squared actual signal
    	double nominalCrack = 0.0;    // accumulate squared possible full signal
    	
    	// PARA 0.25
    	int maxSpike = (int) Math.round(spikeWidth*(tuMillis/1000)*((double) w.frameRate/framesPerSlice));
    	int maxCrack = (int) Math.round(crackWidth*(tuMillis/1000)*((double) w.frameRate/framesPerSlice));
    	new Debug("max nof. slices in spike: %d", maxSpike);
    	new Debug("max nof. slices in crack: %d", maxCrack);

        for (int k = 0 + jDash; k < sigSize - jDash; k++) {
        	
        	final double thr = threshold(decoder, level, flo[k], cei[k]);
        	final TwoDoubles r = lsq(sig, k, halfWidth, wDot);

        	final boolean high = r.a > thr;
        	final boolean low = !high;
        	
        	final double tSec = timeSeconds(k);
        	if (plotEntries != null) {
        		plotEntries.updateAmplitudes(tSec, r.a);
        	}

        	if (state == States.LOW) {
        		if (low) {
        			continue;
        		}
        		else if (high) {
        			kRaise = k;
        			accSpike = Compute.squared(r.a - flo[k]);
        			nominalSpike = Compute.squared(cei[k] - flo[k]);
        			state = States.M_HIGH;
        		}
        	}
        	else if (state == States.M_HIGH) {
        		if (low) {
        			// thin spike gets ignored
        			state = States.LOW;
        		}
        		else if (high) {
        			if (k - kRaise >= maxSpike) {
        				state = States.HIGH;
        			}
    				accSpike += Compute.squared(r.a - flo[k]);
    				nominalSpike += Compute.squared(cei[k] - flo[k]);
        		}
        	}
        	else if (state == States.HIGH) {
        		if (low) {
        			kDrop = k;
        			state = States.M_LOW;
        			accCrack = Compute.squared(r.a - flo[k]);
        			nominalCrack = Compute.squared(cei[k] - flo[k]);
        		}
        		else if (high) {
    				accSpike += Compute.squared(r.a - flo[k]);
    				nominalSpike += Compute.squared(cei[k] - flo[k]);
        		}
        	}
        	else if (state == States.M_LOW) {
        		if (low) {
        			if (k - kDrop >= maxCrack) {
        				state = States.LOW;
						final boolean createDot =
								(kDrop - kRaise)*tsLength <
								GerkeDecoder.DASH_LIMIT[DecoderIndex.LSQ2.ordinal()];
						if (accSpike/nominalSpike < Compute.squared(spikeLimit)) {
							// reject a tone with too little energy
							continue;
						}
						else {
							final int kMiddle = (int) Math.round((kRaise + kDrop) / 2.0);
							if (createDot) {
								tones.put(Integer.valueOf(kMiddle), new Dot(kMiddle, kRaise, kDrop));
							} else {
								tones.put(Integer.valueOf(kMiddle), new Dash(kMiddle, kRaise, kDrop));
							}
						}
        			}
        			else {
        				accCrack += Compute.squared(r.a - flo[k]);
        				nominalCrack += Compute.squared(cei[k] - flo[k]);
        			}
        		}
        		else if (high) {
        			if (accCrack/nominalCrack > Compute.squared(crackDipLimit)) {
        				// just a weak crack, ignore it
            			state = States.HIGH;
        				accSpike += Compute.squared(r.a - flo[k]);
        				nominalSpike += Compute.squared(cei[k] - flo[k]);
        			}
        			else {
        				// not weak; generate a tone after all
        				final boolean createDot =
								(kDrop - kRaise)*tsLength <
								GerkeDecoder.DASH_LIMIT[DecoderIndex.LSQ2.ordinal()];
						final int kMiddle = (int) Math.round((kRaise + kDrop) / 2.0);
						if (createDot) {
							tones.put(Integer.valueOf(kMiddle), new Dot(kMiddle, kRaise, kDrop));
						} else {
							tones.put(Integer.valueOf(kMiddle), new Dash(kMiddle, kRaise, kDrop));
						}
						
						state = States.M_HIGH;
	        			accSpike = Compute.squared(r.a - flo[k]);
	        			nominalSpike = Compute.squared(cei[k] - flo[k]);
						kRaise = k;
        			}

        		}
        	}
        	
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
