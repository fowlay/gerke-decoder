package st.foglo.gerke_decoder.decoder.tone_silence;

import st.foglo.gerke_decoder.GerkeDecoder;
import st.foglo.gerke_decoder.GerkeLib;
import st.foglo.gerke_decoder.GerkeDecoder.DecoderIndex;
import st.foglo.gerke_decoder.GerkeLib.Death;
import st.foglo.gerke_decoder.decoder.DecoderBase;
import st.foglo.gerke_decoder.decoder.Node;
import st.foglo.gerke_decoder.decoder.Trans;
import st.foglo.gerke_decoder.format.Formatter;
import st.foglo.gerke_decoder.plot.PlotEntries;
import st.foglo.gerke_decoder.wave.Wav;

public final class ToneSilenceDecoder extends DecoderBase {

    public static final double THRESHOLD = 0.524;

    final Trans[] trans;
    final int transIndex;

    final int decoder = DecoderIndex.TONE_SILENCE.ordinal();
    
    final int wordSpaceLimit =
            (int) Math.round(spExp*GerkeDecoder.WORD_SPACE_LIMIT[decoder]*tuMillis*w.frameRate/(1000*framesPerSlice));

    final int charSpaceLimit =
            (int) Math.round(spExp*GerkeDecoder.CHAR_SPACE_LIMIT[decoder]*tuMillis*w.frameRate/(1000*framesPerSlice));   // PARAMETER

    final int dashLimit = (int) Math.round(GerkeDecoder.DASH_LIMIT[decoder]*tuMillis*w.frameRate/(1000*framesPerSlice));        // PARAMETER



    public ToneSilenceDecoder(
            double tuMillis,
            int framesPerSlice,
            double tsLength,
            int offset,
            Wav w,
            double[] sig,
            PlotEntries plotEntries,
            Formatter formatter,

            double ceilingMax,
            int nofSlices,
            double level,
            double[] cei,
            double[] flo
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
                ceilingMax,
                THRESHOLD
                );

        this.trans = findTransitions(
//                tuMillis,
//                tsLength,
                nofSlices,
//                framesPerSlice,
//                w,
                GerkeDecoder.DecoderIndex.TONE_SILENCE.ordinal(),
                level,
//                sig,
//                cei,
                flo);
        this.transIndex = trans.length;


    }

    @Override
    public void execute() throws Exception {

        if (plotEntries != null) {

            boolean firstLap = true;
            for (int t = 0; t < transIndex; t++) {

                final double sec = w.secondsFromSliceIndex(trans[t].q, framesPerSlice);

                if (plotEntries.plotBegin <= sec && sec <= plotEntries.plotEnd) {
                    plotEntries.addDecoded(sec, (trans[t].rise ? 2 : 1)*ceilingMax/20);
                }

                // initial point on the decoded curve
                if (firstLap) {
                    plotEntries.addDecoded(plotEntries.plotBegin, (trans[t].rise ? 1 : 2)*ceilingMax/20);
                    firstLap = false;
                }
            }
        }

        boolean prevTone = false;

        Node p = Node.tree;

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

                    wpm.spTicksW += trans[t].q - trans[t-1].q;
                    wpm.spCusW += 7;
                    wpm.chTicks += trans[t-1].q - qCharBegin;
                    wpm.chCus += p.nTus;
                    qCharBegin = trans[t].q;

                    p = Node.tree;
                }
                else if (trans[t].q - trans[t-1].q > charSpaceLimit) {
                    formatter.add(false, p.text, -1);

                    wpm.spTicksC += trans[t].q - trans[t-1].q;
                    wpm.spCusC += 3;
                    wpm.chTicks += trans[t-1].q - qCharBegin;
                    wpm.chCus += p.nTus;
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

            wpm.chTicks += trans[transIndex-1].q - qCharBegin;
            wpm.chCus += p.nTus;
        }
        else if (p == Node.tree && formatter.getPos() > 0) {
            formatter.flush();
            formatter.newLine();
        }

        wpm.report();
    }

}
