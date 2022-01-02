package st.foglo.gerke_decoder.decoder.tone_silence;

import st.foglo.gerke_decoder.GerkeDecoder;
import st.foglo.gerke_decoder.GerkeLib;
import st.foglo.gerke_decoder.GerkeDecoder.decoderIndex;
import st.foglo.gerke_decoder.GerkeLib.Death;
import st.foglo.gerke_decoder.decoder.DecoderBase;
import st.foglo.gerke_decoder.decoder.Node;
import st.foglo.gerke_decoder.decoder.Trans;
import st.foglo.gerke_decoder.format.Formatter;
import st.foglo.gerke_decoder.plot.PlotEntries;
import st.foglo.gerke_decoder.wave.Wav;

public final class ToneSilenceDecoder extends DecoderBase {

	final Trans[] trans;
	final int transIndex;
	
	final double ceilingMax;
	
	final int decoder = decoderIndex.DIPS_FINDING.ordinal();
	final int wordSpaceLimit =
			(int) Math.round(GerkeDecoder.WORD_SPACE_LIMIT[decoder]*tuMillis*w.frameRate/(1000*framesPerSlice));   // PARAMETER
	
	final int charSpaceLimit = (int) Math.round(GerkeDecoder.CHAR_SPACE_LIMIT[decoder]*tuMillis*w.frameRate/(1000*framesPerSlice));   // PARAMETER
	
	final int dashLimit = (int) Math.round(GerkeDecoder.DASH_LIMIT[decoder]*tuMillis*w.frameRate/(1000*framesPerSlice));        // PARAMETER
    

	
	public ToneSilenceDecoder(
			double tuMillis,
			int framesPerSlice,
			double tsLength,
			int offset,
			Wav w,
			double[] sig,
			PlotEntries plotEntries,
			double[] plotLimits,
			Formatter formatter,
			
			Trans[] trans,
			int transIndex,
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
    			formatter
				);
		
		this.trans = trans;
		this.transIndex = transIndex;
		this.ceilingMax = ceilingMax;
		
		
	}
	
	
	@Override
	public void execute() throws Exception {
		
        if (plotEntries != null) {

            boolean firstLap = true;
            for (int t = 0; t < transIndex; t++) {

                final double sec = timeSeconds(trans[t].q, framesPerSlice, w.frameRate, w.offsetFrames);

                if (plotLimits[0] <= sec && sec <= plotLimits[1]) {
                    plotEntries.addDecoded(sec, (trans[t].rise ? 2 : 1)*ceilingMax/20);
                }

                // initial point on the decoded curve
                if (firstLap) {
                    plotEntries.addDecoded(plotLimits[0], (trans[t].rise ? 1 : 2)*ceilingMax/20);
                    firstLap = false;
                }
            }
        }

        boolean prevTone = false;

        Node p = Node.tree;

        int spTicksW = 0;   // inter-word spaces: time-slice ticks
        int spCusW = 0;     // inter-word spaces: code units
        int spTicksC = 0;   // inter-char spaces: time-slice ticks
        int spCusC = 0;     // inter-char spaces: code units
        int chTicks = 0;    // characters: time-slice ticks
        int chCus = 0;      // spaces: character units

        int qCharBegin = -1;

        for (int t = 0; t < transIndex; t++) {

            final boolean newTone = trans[t].rise;

            if (!prevTone && newTone) {
                // silent -> tone
                if (t == 0) {
                    p = Node.tree;
                    qCharBegin = trans[t].q;
                }
                else if (trans[t].q - trans[t-1].q > wordSpaceLimit) {
                    if (GerkeLib.getFlag(GerkeDecoder.O_TSTAMPS)) {
                        formatter.add(true,
                                p.text,
                                offset + (int) Math.round(trans[t].q*tsLength*tuMillis/1000));
                    }
                    else {
                        formatter.add(true, p.text, -1);
                    }

                    spTicksW += trans[t].q - trans[t-1].q;
                    spCusW += 7;
                    chTicks += trans[t-1].q - qCharBegin;
                    chCus += p.nTus;
                    qCharBegin = trans[t].q;

                    p = Node.tree;
                }
                else if (trans[t].q - trans[t-1].q > charSpaceLimit) {
                    formatter.add(false, p.text, -1);

                    spTicksC += trans[t].q - trans[t-1].q;
                    spCusC += 3;
                    chTicks += trans[t-1].q - qCharBegin;
                    chCus += p.nTus;
                    qCharBegin = trans[t].q;

                    p = Node.tree;
                }
            }
            else if (prevTone && !newTone) {
                // tone -> silent
                final int dashSize = trans[t].q - trans[t-1].q;
                if (dashSize > dashLimit) {
                    p = p.newNode("-");
                }
                else {
                    p = p.newNode(".");
                }
            }
            else {
                new Death("internal error");
            }

            prevTone = newTone;
        }

        if (p != Node.tree) {
            formatter.add(true, p.text, -1);
            formatter.newLine();

            chTicks += trans[transIndex-1].q - qCharBegin;
            chCus += p.nTus;
        }
        else if (p == Node.tree && formatter.getPos() > 0) {
            formatter.flush();
            formatter.newLine();
        }

        wpmReport(chCus, chTicks, spCusW, spTicksW, spCusC, spTicksC, tuMillis, tsLength);


	}

}
