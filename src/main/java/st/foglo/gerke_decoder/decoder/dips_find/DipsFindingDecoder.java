package st.foglo.gerke_decoder.decoder.dips_find;

import st.foglo.gerke_decoder.GerkeLib;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.SortedSet;
import java.util.TreeSet;

import st.foglo.gerke_decoder.GerkeDecoder;
import st.foglo.gerke_decoder.GerkeDecoder.HiddenOpts;
import st.foglo.gerke_decoder.GerkeDecoder.DecoderIndex;
import st.foglo.gerke_decoder.GerkeLib.Death;
import st.foglo.gerke_decoder.GerkeLib.Debug;
import st.foglo.gerke_decoder.decoder.DecoderBase;
import st.foglo.gerke_decoder.decoder.Node;
import st.foglo.gerke_decoder.decoder.Trans;
import st.foglo.gerke_decoder.format.Formatter;
import st.foglo.gerke_decoder.lib.Compute;
import st.foglo.gerke_decoder.plot.PlotEntries;
import st.foglo.gerke_decoder.plot.PlotEntryDecode;
import st.foglo.gerke_decoder.wave.Wav;

public final class DipsFindingDecoder extends DecoderBase {

    public static final double THRESHOLD = 0.524;

    final Trans[] trans;
    final int transIndex;

    final int decoder = DecoderIndex.DIPS_FINDING.ordinal();
    
    final int wordSpaceLimit =
            (int) Math.round(spExp*GerkeDecoder.WORD_SPACE_LIMIT[decoder]*tuMillis*w.frameRate/(1000*framesPerSlice));

    final int charSpaceLimit =
            (int) Math.round(spExp*GerkeDecoder.CHAR_SPACE_LIMIT[decoder]*tuMillis*w.frameRate/(1000*framesPerSlice));

    public DipsFindingDecoder(
            double tuMillis,
            int framesPerSlice,
            double tsLength,
            int offset,
            Wav w,
            double[] sig,
            PlotEntries plotEntries,
            Formatter formatter,

            double ceilingMax,
//            Trans[] trans,
//            int transIndex,
            double[] cei,
            double[] flo,
            int nofSlices,
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
                decoder,
                level,
//                sig,
//                cei,
                flo);
        this.transIndex = trans.length;

    }

    @Override
    public void execute() throws IOException, InterruptedException {

        /**
-        * Merge dips when closer than this distance. Unit is TUs.
-        */
        final double dipMergeLim = GerkeLib.getDoubleOptMulti(GerkeDecoder.O_HIDDEN)[HiddenOpts.DIP_MERGE_LIM.ordinal()];

        /**
         * Dips of lesser strength are ignored. A too high value will cause
         * weak dips to be ignored, so that 'i' prints as 't' for example.
         */
        final double dipStrengthMin = GerkeLib.getDoubleOptMulti(GerkeDecoder.O_HIDDEN)[HiddenOpts.DIP_STRENGTH_MIN.ordinal()];

        if (plotEntries != null) {
            // make one "decode" entry at left edge of plot
            plotEntries.addDecoded(
                    plotEntries.plotBegin, PlotEntryDecode.height*ceilingMax);
        }

        int charNo = 0;

        // find characters
        // this is similar to what the level decoder does
        boolean prevTone = false;
        int beginChar = -1;

        for (int t = 0; t < transIndex; t++) {
            final boolean newTone = trans[t].rise;
            if (t == 0 && !(trans[t].rise)) {
                new Death("assertion failure, first transition is not a rise");
            }

            if (!prevTone && newTone) {
                // silent -> tone
                if (t == 0) {
                    beginChar = trans[t].q;
                }
                else if (trans[t].q - trans[t-1].q > wordSpaceLimit) {
                    decodeGapChar(beginChar, trans[t-1].q, sig, cei, flo, tsLength,
                            dipMergeLim, dipStrengthMin,
                            charNo++,
                            formatter, plotEntries, ceilingMax);
                    wpm.chTicks += trans[t-1].q - beginChar;
                    final int ts =
                            GerkeLib.getFlag(GerkeDecoder.O_TSTAMPS) ?
                            offset + (int) Math.round(trans[t].q*tsLength*tuMillis/1000) : -1;
                    formatter.add(true, "", ts);
                    beginChar = trans[t].q;
                    wpm.spCusW += 7;
                    wpm.spTicksW += trans[t].q - trans[t-1].q;
                }
                else if (trans[t].q - trans[t-1].q > charSpaceLimit) {
                    decodeGapChar(beginChar, trans[t-1].q, sig, cei, flo, tsLength,
                            dipMergeLim, dipStrengthMin,
                            charNo++,
                            formatter, plotEntries, ceilingMax);
                    wpm.chTicks += trans[t-1].q - beginChar;
                    beginChar = trans[t].q;
                    wpm.spCusC += 3;
                    wpm.spTicksC += trans[t].q - trans[t-1].q;
                }
            }

            prevTone = newTone;
        }
        // prevTone is presumably a fall; if so, use it for the very last character
        // else the last char is incomplete; lose it
        if (trans[transIndex-1].rise == false) {
            decodeGapChar(beginChar, trans[transIndex-1].q, sig, cei, flo, tsLength,
                    dipMergeLim, dipStrengthMin,
                    charNo++,
                    formatter, plotEntries, ceilingMax);
            wpm.chTicks += trans[transIndex-1].q - beginChar;
        }

        formatter.flush();
        formatter.newLine();

        wpm.report();
    }



private void decodeGapChar(
        int q1,
        int q2,
        double[] sig,
        double[] cei,
        double[] flo,
        double tau,
        double dipMergeLim,
        double dipStrengthMin,
        int charNo,
        Formatter formatter,
        PlotEntries plotEntries,
        double ceilingMax) throws IOException, InterruptedException {

    final boolean inView = plotEntries != null &&
            w.secondsFromSliceIndex(q1, framesPerSlice) >= plotEntries.plotBegin &&
            w.secondsFromSliceIndex(q2, framesPerSlice) <= plotEntries.plotEnd;

    final double decodeLo = ceilingMax*PlotEntryDecode.height;
    final double decodeHi = ceilingMax*2*PlotEntryDecode.height;

    if (inView) {
        plotEntries.addDecoded(w.secondsFromSliceIndex(q1, framesPerSlice), decodeLo);
        plotEntries.addDecoded(w.secondsFromSliceIndex(q1+1, framesPerSlice), decodeHi);
        plotEntries.addDecoded(w.secondsFromSliceIndex(q2-1, framesPerSlice), decodeHi);
        plotEntries.addDecoded(w.secondsFromSliceIndex(q2, framesPerSlice), decodeLo);
    }

    final int halfTu = (int) Math.round(1.0/(2*tau));
    final int fatHalfTu = (int) Math.round(1.0/(2*tau));  // no need to stretch??
    final int k1 = q1 + halfTu;
    final int k2 = q2 - halfTu;

    // Maximum number of dips, stop collecting if exceeded
    final int countMax = (int) Math.round(((q2 - q1)*tau + 3)/2);

    int strengthSize = k2 - k1;
    final double[] strength = new double[Compute.iMax(strengthSize, 0)];

    // this makes normalized strengths fairly independent of sigma
    final double z = (cei[k1] - flo[k1] + cei[k2] - flo[k2])*(1/(2*tau))*GerkeDecoder.P_DIP_SIGMA;

    for (int k = k1; k < k2; k++) {
        // compute a weighted sum
        double acc = (cei[k] - sig[k]);
        for (int d = 1; true; d++) {
            double w = Math.exp(-Compute.squared(d*tau/GerkeDecoder.P_DIP_SIGMA)/2);
            if (w < GerkeDecoder.P_DIP_EXPTABLE_LIM || k-d < 0 || k+d >= sig.length) {
                break;
            }
            acc += w*((cei[k] - sig[k+d]) + (cei[k] - sig[k-d]));
        }
        strength[k-k1] = acc/z;   // normalized strength
    }

    final SortedSet<Dip> dips = new TreeSet<Dip>();
    double pprev = 0.0;
    double prev = 0.0;
    Dip prevDip = new DipByStrength(q1-fatHalfTu, 9999.8);  // virtual dip to the left
    for (int k = k1; k < k2; k++) {
        final double s = strength[k-k1];
        if (s < prev && prev > pprev) {   // cannot succeed at k == k1
            // a summit at prev
            final Dip d = new DipByStrength(k-1, prev);

            // if we are very close to the previous dip, then merge
            if (d.q - prevDip.q < dipMergeLim*2*halfTu) {
                prevDip = new DipByStrength((d.q + prevDip.q)/2, Compute.dMax(d.strength, prevDip.strength));
            }
            else {
                dips.add(prevDip);
                prevDip = d;
            }
        }
        pprev = prev;
        prev = s;
    }

    dips.add(prevDip);
    dips.add(new DipByStrength(q2+fatHalfTu, 9999.9)); // virtual dip to the right

    // build collection of significant dips
    final SortedSet<Dip> dipsByTime = new TreeSet<Dip>();
    int count = 0;
    for (Dip d : dips) {
        if (count >= countMax+1) {
            new Debug("char: %d, count: %d, max: %d", charNo, count, countMax);
            break;
        }
        else if (d.strength < dipStrengthMin) {
            break;
        }
        dipsByTime.add(new DipByTime(d.q, d.strength));
        count++;

        if (inView && d.strength < 9999.8) {
            plotEntries.addDecoded(w.secondsFromSliceIndex(d.q - halfTu, framesPerSlice), decodeHi);
            plotEntries.addDecoded(w.secondsFromSliceIndex(d.q - halfTu + 1, framesPerSlice), decodeLo);
            plotEntries.addDecoded(w.secondsFromSliceIndex(d.q + halfTu - 1, framesPerSlice), decodeLo);
            plotEntries.addDecoded(w.secondsFromSliceIndex(d.q + halfTu, framesPerSlice), decodeHi);
        }
    }

    // interpretation follows!
    Node p = Node.tree;
    count = 0;
    int prevQ = -1;

    final List<Beep> beeps = new ArrayList<Beep>();
    int extentMax = -1;

    for (Dip d : dipsByTime) {
        if (count == 0) {
            prevQ = d.q;
        }
        else  {
            final int extent = d.q - prevQ;

            if (extent > GerkeDecoder.P_DIP_TWODASHMIN*halfTu) {
                // assume two dashes with a too weak dip in between
                final int e1 = extent/2;
                final int e2 = extent - e1;
                beeps.add(new Beep(e1));
                beeps.add(new Beep(e2));
                extentMax = Compute.iMax(extentMax, e2);
            }
            else {

                beeps.add(new Beep(extent));
                extentMax = Compute.iMax(extentMax, extent);
            }
        }
        prevQ = d.q;
        count++;
    }

    final double extentMaxD = extentMax;

    if (beeps.size() == 1) {
        // single beep
        if (beeps.get(0).extent > GerkeDecoder.P_DIP_DASHMIN*halfTu) {
            p = p.newNode("-");
        }
        else {
            p = p.newNode(".");
        }
    }
    else if (extentMax <= GerkeDecoder.P_DIP_DASHMIN*halfTu) {
        // only dots, two or more of them
        for (int m = 0; m < beeps.size(); m++) {
            p = p.newNode(".");
        }
    }
    else {
        // at least one dash
        for (Beep beep : beeps) {
            if (beep.extent/extentMaxD > GerkeDecoder.P_DIP_DASHQUOTIENT) {
                p = p.newNode("-");
            }
            else {
                p = p.newNode(".");
            }
        }
    }

    new Debug("char no: %d, decoded: %s", charNo, p.text);
    formatter.add(false, p.text, -1);
    wpm.chCus += p.nTus;
}

}
