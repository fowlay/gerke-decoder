package st.foglo.gerke_decoder.decoder.pattern_match;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.Map.Entry;

import st.foglo.gerke_decoder.GerkeDecoder;
import st.foglo.gerke_decoder.GerkeLib;
import st.foglo.gerke_decoder.GerkeDecoder.decoderIndex;
import st.foglo.gerke_decoder.GerkeLib.Death;
import st.foglo.gerke_decoder.GerkeLib.Info;
import st.foglo.gerke_decoder.decoder.DecoderBase;
import st.foglo.gerke_decoder.decoder.Trans;
import st.foglo.gerke_decoder.format.Formatter;
import st.foglo.gerke_decoder.lib.Compute;
import st.foglo.gerke_decoder.plot.PlotEntries;
import st.foglo.gerke_decoder.plot.PlotEntryDecode;
import st.foglo.gerke_decoder.wave.Wav;

public final class PatternMatchDecoder extends DecoderBase {
	
	final double ceilingMax;
	
	final Trans[] trans;
	final int transIndex;
	
	final int decoder = decoderIndex.PATTERN_MATCHING.ordinal();
	
	final int wordSpaceLimit =
			(int) Math.round(GerkeDecoder.WORD_SPACE_LIMIT[decoder]*tuMillis*w.frameRate/(1000*framesPerSlice));   // PARAMETER
	
	final int charSpaceLimit = (int) Math.round(GerkeDecoder.CHAR_SPACE_LIMIT[decoder]*tuMillis*w.frameRate/(1000*framesPerSlice));   // PARAMETER

	final int ampMap;
	final double level;
	final double levelLog;
	
	public PatternMatchDecoder(
			
			double tuMillis,
			int framesPerSlice,
			double tsLength,
			int offset,
			Wav w,
			double[] sig,
			PlotEntries plotEntries,
			double[] plotLimits,
			Formatter formatter,
			
			double ceilingMax,
			Trans[] trans,
			int transIndex,
			int ampMap,
			double level
			
			
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
    			formatter
				);
		
		this.ceilingMax = ceilingMax;
		
		this.trans = trans;
		this.transIndex = transIndex;
		
		this.ampMap = ampMap;
		this.level = level;
		this.levelLog = Math.log(level);
		

		
	}

	@Override
	public void execute() throws Exception {
		
        if (plotEntries != null) {
            // make one "decode" entry at left edge of plot
            plotEntries.addDecoded(plotLimits[0], PlotEntryDecode.height*ceilingMax);
        }

        final List<CharData> cdList = new ArrayList<CharData>();
        CharData charCurrent = null;
        boolean prevTone = false;
        for (int t = 0; t < transIndex; t++) {

            final boolean newTone = trans[t].rise;

            if (!prevTone && newTone) {                          // silent -> tone
                if (t == 0) {
                    charCurrent = new CharData(trans[t]);
                }
                else if (trans[t].q - trans[t-1].q > wordSpaceLimit) {
                    cdList.add(charCurrent);
                    cdList.add(new CharData());                  // empty CharData represents word space
                    charCurrent = new CharData(trans[t]);
                }
                else if (trans[t].q - trans[t-1].q > charSpaceLimit) {
                    cdList.add(charCurrent);
                    charCurrent = new CharData(trans[t]);
                }
                else {
                    charCurrent.add(trans[t]);
                }
            }
            else if (prevTone && !newTone) {                     // tone -> silent
                charCurrent.add(trans[t]);
            }
            else {
                new Death("internal error");
            }

            prevTone = newTone;
        }

        if (!charCurrent.isEmpty()) {
            if (charCurrent.isComplete()) {
                cdList.add(charCurrent);
            }
        }



        int ts = -1;

        int tuCount = 0;  // counters for effective WPM determination
        int qBegin = -1;
        int qEnd = 0;



        for (CharData cd : cdList) {
            qBegin = qBegin == -1 ? cd.transes.get(0).q : qBegin;
            if (cd.isEmpty()) {
                formatter.add(true, "", ts);
                tuCount += 4;
            }
            else {
                ts = GerkeLib.getFlag(GerkeDecoder.O_TSTAMPS) ?
                        offset + (int) Math.round(cd.transes.get(0).q*tsLength*tuMillis/1000) : -1;
                        final CharTemplate ct = decodeCharByPattern(decoder, ampMap, cd, sig, level, levelLog, tsLength, offset, tuMillis);
                        tuCount += tuCount == 0 ? 0 : 3;

                        // TODO, the 5 below represents the undecodable character; compute
                        // a value from the cd instead
                        tuCount += ct == null ? 5 : ct.pattern.length;
                        final int transesSize = cd.transes.size();
                        qEnd = cd.transes.get(transesSize-1).q;
                        formatter.add(false, ct == null ? "???" : ct.text, -1);

                        // if we are plotting, then collect some things here
                        if (plotEntries != null && ct != null) {
                            final double seconds = offset + timeSeconds(cd.transes.get(0).q, framesPerSlice, w.frameRate, w.offsetFrames);
                            if (plotLimits[0] <= seconds && seconds <= plotLimits[1]) {
                                plotDecoded(plotEntries, cd, ct, offset, (tsLength*tuMillis)/1000, ceilingMax,
                                        framesPerSlice, w.frameRate, w.offsetFrames);
                            }
                        }
            }
        }
        formatter.flush();
        if (formatter.getPos() > 0) {
            formatter.newLine();
        }

        final double totalTime = (qEnd - qBegin)*tsLength*tuMillis;  // ms
        final double effWpm = 1200.0/(totalTime/tuCount);            // 1200 / (TU in ms)
        new Info("effective WPM: %.1f", effWpm);



	}
	
    private static CharTemplate decodeCharByPattern(
            int decoder,
            int ampMap,
            CharData cd,
            double[] sig, double level, double levelLog,
            double tsLength, int offset, double tuMillis) {

        final int qSize = cd.getLastAdded().q - cd.transes.get(0).q;
        final int tuClass = (int) Math.round(tsLength*qSize);

        // PARAMETER 1.00, skew the char class a little, heuristic
        final double tuClassD = 1.00*tsLength*qSize;

        final Set<Entry<Integer, List<CharTemplate>>> candsAll = CharTemplate.templs.entrySet();

        final double zigma = 0.7; // PARAMETER
        CharTemplate best = null;
        double bestSum = -999999.0;
        for (Entry<Integer, List<CharTemplate>> eict : candsAll) {

            final int j = eict.getKey().intValue();

            final double weight = Math.exp(-Compute.squared((tuClassD - j)/zigma)/2);


            for (CharTemplate cand : eict.getValue()) {

                int candSize = cand.pattern.length;
                double sum = 0.0;

                double deltaCeiling = cd.getLastAdded().ceiling - cd.transes.get(0).ceiling;
                double deltaFloor = cd.getLastAdded().floor - cd.transes.get(0).floor;

                double slopeCeiling = deltaCeiling/qSize;
                double slopeFloor = deltaFloor/qSize;
                for (int q = cd.transes.get(0).q; q < cd.getLastAdded().q; q++) {

                    int qq = q - cd.transes.get(0).q;

                    double ceiling = cd.transes.get(0).ceiling + slopeCeiling*qq;

                    double floor = cd.transes.get(0).floor + slopeFloor*qq;

                    int indx = (candSize*qq)/qSize;

                    double u = sig[q] - threshold(decoder, ampMap, level, levelLog, floor, ceiling);


                    // sum += Math.signum(u)*u*u*cand.pattern[indx];
                    sum += u*cand.pattern[indx];
                }

                if (weight*sum > bestSum) {
                    bestSum = weight*sum;
                    best = cand;
                }
            }
        }

        if (GerkeLib.getIntOpt(GerkeDecoder.O_VERBOSE) >= 3) {
            System.out.println(
                    String.format(
                            "character result: %s, time: %d, class: %d, size: %d",
                            best != null ? best.text : "null",
                            offset + (int) Math.round(cd.transes.get(0).q*tsLength*tuMillis/1000),
                            tuClass,
                            qSize));
        }

        return best;
    }
    
    /**
     * Make plot entries for a decoded character
     * @param plotEntries
     * @param cd
     * @param ct
     * @param offset
     * @param d
     * @param ceilingMax
     */
    private static void plotDecoded(
            PlotEntries plotEntries,
            CharData cd,
            CharTemplate ct,
            int offset, double tsSecs, double ceilingMax,
            int framesPerSlice, int frameRate, int offsetFrames) {

        int prevValue = CharTemplate.LO;

        final int nTranses = cd.transes.size();

        // time of 1 char
        final double tChar = (cd.transes.get(nTranses-1).q - cd.transes.get(0).q)*tsSecs;

        for (int i = 0; i < ct.pattern.length; i++) {
            if (ct.pattern[i] == CharTemplate.HI && prevValue == CharTemplate.LO) {
                // a rise

                final double t1 =
                        timeSeconds(cd.transes.get(0).q, framesPerSlice, frameRate, offsetFrames) +
                           i*(tChar/ct.pattern.length);
                final double t2 = t1 + 0.1*tsSecs;

                // add to plotEntries

//				plotEntries.put(new Double(t1), makeOne(1, ceilingMax));
//				plotEntries.put(new Double(t2), makeOne(2, ceilingMax));
                plotEntries.addDecoded(t1, PlotEntryDecode.height*ceilingMax);
                plotEntries.addDecoded(t2, 2*PlotEntryDecode.height*ceilingMax);

            }
            else if (ct.pattern[i] == CharTemplate.LO && prevValue == CharTemplate.HI) {
                // a fall

                // compute points in time
                final double t1 =
                        timeSeconds(cd.transes.get(0).q, framesPerSlice, frameRate, offsetFrames) +
                           i*(tChar/ct.pattern.length);
                final double t2 = t1 + 0.1*tsSecs;

                // add to plotEntries

//				plotEntries.put(new Double(t1), makeOne(2, ceilingMax));
//				plotEntries.put(new Double(t2), makeOne(1, ceilingMax));
                plotEntries.addDecoded(t1, 2*PlotEntryDecode.height*ceilingMax);
                plotEntries.addDecoded(t2, PlotEntryDecode.height*ceilingMax);
            }
            prevValue = ct.pattern[i];
        }

        final double t1 = timeSeconds(cd.transes.get(nTranses-1).q, framesPerSlice, frameRate, offsetFrames);
        final double t2 = t1 + 0.1*tsSecs;
//		plotEntries.put(new Double(t1), makeOne(2, ceilingMax));
//		plotEntries.put(new Double(t2), makeOne(1, ceilingMax));

        plotEntries.addDecoded(t1, 2*PlotEntryDecode.height*ceilingMax);
        plotEntries.addDecoded(t2, PlotEntryDecode.height*ceilingMax);
    }


}
