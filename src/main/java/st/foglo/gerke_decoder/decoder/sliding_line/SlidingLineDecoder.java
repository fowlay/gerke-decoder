package st.foglo.gerke_decoder.decoder.sliding_line;

import java.util.NavigableMap;
import java.util.TreeMap;

import st.foglo.gerke_decoder.GerkeLib;
import st.foglo.gerke_decoder.GerkeDecoder;
import st.foglo.gerke_decoder.GerkeDecoder.decoderIndex;
import st.foglo.gerke_decoder.decoder.DecoderBase;
import st.foglo.gerke_decoder.decoder.Node;
import st.foglo.gerke_decoder.decoder.TwoDoubles;
import st.foglo.gerke_decoder.format.Formatter;
import st.foglo.gerke_decoder.plot.PlotEntries;
import st.foglo.gerke_decoder.wave.Wav;

public final class SlidingLineDecoder extends DecoderBase {
	
	final int sigSize;
	final int ampMap;
	
	final double[] flo;
	
	final double level;
	final double levelLog;

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
			int ampMap,
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
    			ceilingMax
				);
		
		this.sigSize = sigSize;
		this.ampMap = ampMap;
		
		this.flo = flo;
		this.level = level;
		this.levelLog = Math.log(level);
		
	}
	
	
	
	@Override
	public void execute() {

    	final int decoder = decoderIndex.LSQ2.ordinal();

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

        final WeightBase wDot = new WeightDot(jDot);  // arg ignored really

        int highBegin = -999;
        boolean isHigh = false;
        double acc = 0.0;
        double accMax = 0.0;
        for (int k = 0 + jDash; k < sigSize - jDash; k++) {
        	
        	final double thr = threshold(decoder, ampMap, level, levelLog, flo[k], cei[k]);
        	
        	final TwoDoubles r = lsq(sig, k, jDot, wDot);

        	final boolean high = testAndUpdate(r.a, thr);

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
        	else if (isHigh && !high && ((k - highBegin)*tsLength < 0.15 || acc < 0.04*accMax)) {  // PARA PARA
        		// ignore very thin dot
        		isHigh = false;
        		highBegin = k;
        		acc = 0.0; accMax = 0.0;
        	}
        	else if (isHigh && !high &&
        			(k - highBegin)*tsLength < GerkeDecoder.DASH_LIMIT[decoderIndex.LSQ2.ordinal()]) {
        		// create Dot
        		final int kMiddle = (int) Math.round((highBegin + k)/2.0);
        		dashes.put(Integer.valueOf(kMiddle),
        				new Dot(kMiddle, highBegin, k));

        		isHigh = false;
        		acc = 0.0; accMax = 0.0;
        	}
        	else if (isHigh && !high) {
        		// create Dash
        		final int kMiddle = (int) Math.round((highBegin + k)/2.0);
        		dashes.put(Integer.valueOf(kMiddle),
        				new Dash(kMiddle, highBegin, k, r.a));
        		// TODO r.a above maybe not used

        		isHigh = false;
        		acc = 0.0; accMax = 0.0;
        	}
        }

        Node p = Node.tree;
        int qCharBegin = -999999;
        Integer prevKey = null;
        for (Integer key : dashes.navigableKeySet()) {

        	if (prevKey == null) {
        		qCharBegin = lsqToneBegin(key, dashes, jDot);
        	}
            if (prevKey != null && toneDist2(prevKey, key, dashes, jDash, jDot) >
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
            else if (prevKey != null && toneDist2(prevKey, key, dashes, jDash, jDot) > GerkeDecoder.CHAR_SPACE_LIMIT[decoder]/tsLength) {
                formatter.add(false, p.text, -1);
                chCus += p.nTus;
                spCusC += 3;
                
                spTicksC += lsqToneBegin(key, dashes, jDot) - lsqToneEnd(prevKey, dashes, jDot);

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
	
    private static boolean testAndUpdate(double value, double thr) {
		return value > thr;
    }

}
