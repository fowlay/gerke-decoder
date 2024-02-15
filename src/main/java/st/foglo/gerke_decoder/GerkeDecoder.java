package st.foglo.gerke_decoder;

// gerke-decoder - translates Morse code audio to text
//
// Copyright (C) 2020-2024 Rabbe Fogelholm
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with this program.  If not, see <http://www.gnu.org/licenses/>.

import java.util.List;
import java.util.Locale;
import java.util.Map.Entry;

import st.foglo.gerke_decoder.GerkeLib.*;
import st.foglo.gerke_decoder.decoder.Decoder;
import st.foglo.gerke_decoder.decoder.DecoderBase;
import st.foglo.gerke_decoder.decoder.dips_find.DipsFindingDecoder;
import st.foglo.gerke_decoder.decoder.least_squares.LeastSquaresDecoder;
import st.foglo.gerke_decoder.decoder.pattern_match.PatternMatchDecoder;
import st.foglo.gerke_decoder.decoder.sliding_line.SlidingLineDecoder;
import st.foglo.gerke_decoder.decoder.sliding_line.SlidingLinePlus;
import st.foglo.gerke_decoder.decoder.tone_silence.ToneSilenceDecoder;
import st.foglo.gerke_decoder.detector.CwDetector;
import st.foglo.gerke_decoder.detector.Signal;
import st.foglo.gerke_decoder.detector.adaptive.CwAdaptiveImpl;
import st.foglo.gerke_decoder.detector.cw_basic.CwBasicImpl;
import st.foglo.gerke_decoder.format.Formatter;
import st.foglo.gerke_decoder.lib.Compute;
import st.foglo.gerke_decoder.plot.CollectorBase;
import st.foglo.gerke_decoder.plot.HistCollector;
import st.foglo.gerke_decoder.plot.HistEntries;
import st.foglo.gerke_decoder.plot.PlotCollector;
import st.foglo.gerke_decoder.plot.PlotCollector.Mode;
import st.foglo.gerke_decoder.plot.PlotEntries;
import st.foglo.gerke_decoder.plot.PlotEntryBase;
import st.foglo.gerke_decoder.plot.PlotEntryDecode;
import st.foglo.gerke_decoder.plot.PlotEntrySig;
import st.foglo.gerke_decoder.plot.PlotEntrySigPlus;
import st.foglo.gerke_decoder.wave.Wav;

public final class GerkeDecoder {

    static final double IGNORE = 0.0;

    private static final double[] TS_LENGTH =
            new double[]{IGNORE, 0.10, 0.10, 0.10, 0.10, 0.10, SlidingLinePlus.TS_LENGTH};



    static final String O_VERSION = "version";
    public static final String O_OFFSET = "offset";
    public static final String O_LENGTH = "length";
    public static final String O_FRANGE = "freq-range";
    public static final String O_FREQ = "freq";
    static final String O_WPM = "wpm";
    public static final String O_SPACE_EXP = "space-expansion";
    public static final String O_CLIPPING = "clip";
    static final String O_STIME = "sample-time";
    public static final String O_SIGMA = "sigma";
    static final String O_DECODER = "decoder";
    public static final String O_LEVEL = "level";
    public static final String O_TSTAMPS = "timestamps";
    public static final String O_TEXT_FORMAT = "text-format";

    public static final String O_PLINT = "plot-interval";
    public static final String O_FPLOT = "plot-frequency";
    public static final String O_APLOT = "plot-amplitude";
    public static final String O_PPLOT = "plot-phase";
    public static final String O_FSPLOT = "plot-frequency-stability";

    public static final String O_HIST_TONE_SPACE = "histogram-tone-space";

    public static final String O_VERBOSE = "verbose";

    public static final String O_COHSIZE = "coherence-size";
    public static final String O_SEGSIZE = "segment-size";

    public static final String O_HIDDEN = "hidden-options";
    public enum HiddenOpts {
        DIP,
        SPIKE,
        BREAK_LONG_DASH,
        FILTER,
        CUTOFF,
        ORDER,
        PHASELOCKED,
        PLWIDTH,
        DIP_MERGE_LIM,
        DIP_STRENGTH_MIN,
        CLIP_DEPTH,
        HALF_WIDTH,
        SPIKE_WIDTH_MAX,
        CRACK_WIDTH_MAX
    };

    static final String[] DECODER_NAME = new String[] {"",
            "tone/silence",
            "patterns",
            "dips",
            "prototype segments",
            "sliding segments",
            "adaptive segments"};

    /**
     * Numeric decoder index 1..6 maps to these names. Do not reorder.
     */
    public enum DecoderIndex {
        ZERO,
        TONE_SILENCE,
        PATTERN_MATCHING,
        DIPS_FINDING,
        LEAST_SQUARES,
        LSQ2,
        LSQ2_PLUS
    };

    public enum DetectorIndex {
        BASIC_DETECTOR,
        ADAPTIVE_DETECTOR
    }

    /**
     * Pauses longer than this denote a word boundary. Unit is TU.
     * Model value: sqrt(3*7) = 4.58
     * words break <---------+---------> words stick
     */
    public static final double[] WORD_SPACE_LIMIT = new double[]{IGNORE, 5.0, 5.0, 5.0, 5.65, 5.6, 5.6};

    /**
     * Pauses longer than this denote a character boundary. Unit is TU.
     * Model value: sqrt(1*3) = 1.73
     * chars break <---------+---------> chars cluster
     */
    public static final double[] CHAR_SPACE_LIMIT = new double[]{IGNORE, 1.65, 1.65, 1.73,
            1.85,
            1.9, 1.9};  // 2.3??

    /**
     * Tones longer than this are interpreted as a dash
     * Model value: sqrt(1*3) = 1.73
     * too many dashes <---------+---------> too few dashes
     */
    public static final double[] DASH_LIMIT = new double[]{IGNORE, 1.8, 1.73, 1.7, 1.745, 1.9, 1.9};

    /**
     * Tones longer than this are interpreted as two dashes
     * (dash dot = 5, dash dash = 7)
     */
    public static final double TWO_DASH_LIMIT = 6.0;

    // Decoder-independent parameters

    /**
     * Default level of clipping: Clipping is made at a level that reduces
     * the total signal content by this fraction.
     */
    public static final double P_CLIP_STRENGTH = 0.05;

    /**
     * Iterative search for a cliplevel is terminated when P_CLIP_STRENGTH
     * is reached to an accuracy given by this parameter. To observe
     * iterations, run with -v -v -v.
     */
    public static final double P_CLIP_PREC = 0.005;


    static final int P_CEIL_HIST = 100;

    static final int P_CEIL_HIST_WIDTH = 30;

    static final double P_CEIL_FOCUS = 45.0; // 50.0;

    static final double P_CEIL_FRAC = 0.70;

    /**
     * When determining floor level, use a histogram of this many
     * slots.
     */
    static final int P_FLOOR_HIST = 100;
    /**
     * When determining the floor level, consider amplitudes in a
     * region of this many TUs.
     */
    static final int P_FLOOR_HIST_WIDTH = 30;

    /**
     * When determining the floor level, remove this fraction of the
     * total of histogram points.
     */
    static final double P_FLOOR_FRAC = 0.70;


    /**
     * When determining the floor level, focus on nearby amplitudes.
     * A larger value widens focus. The unit is number of time slices.
     */
    static final double P_FLOOR_FOCUS = 25.0;

    /**
     * Gaussian sigma for identifying dips. Unit is TUs.
     */
    public static final double P_DIP_SIGMA = 0.15;

    /**
     * Ideally the separation between dips is 4 halfTus for a dot
     * and 8 halfTus for a dash. Increasing the value will cause
     * dashes to be seen as dots.
     */
    public static final double P_DIP_DASHMIN = 6.1;

    /**
     * If two dashes have joined because of a weak dip, the ideal
     * dip separation would be 16 halfTus. If a dash and a dip have
     * joined, the separation is 12 halfTus. To detect the two-dash
     * feature, set a limit that is well above 12.
     */
    public static final double P_DIP_TWODASHMIN = 14.0;

    /**
     * Within a multi-beep character, beep lengths are compared to
     * the length of the longest beep. If the relative length is
     * above this limit, then interpret it as a dash.
     */
    public static final double P_DIP_DASHQUOTIENT = 0.68;

    /**
     * When generating a table of exponential weights, exclude entries
     * lower than this limit.
     */
    public static final double P_DIP_EXPTABLE_LIM = 0.01;

    /**
     * Do not change. Changing this constant would affect the ceiling
     * and floor estimation.
     */
    public static final String STIME_DEFAULT = "0.10";

    public static final String LINE_LENGTH_DEFAULT = "72";


    static {
        /**
         * Note: Align with the top level pom.xml. Also update the
         * version history in README.md.
         */
        new VersionOption("V", O_VERSION, "gerke-decoder version 3.1.6");

        new SingleValueOption("o", O_OFFSET, "0");
        new SingleValueOption("l", O_LENGTH, "-1");

        new SingleValueOption("F", O_FRANGE, "400,1200");
        new SingleValueOption("f", O_FREQ, "-1");

        new SingleValueOption("w", O_WPM, "15.0");
        new SingleValueOption("W", O_SPACE_EXP, "1.0");

        new SingleValueOption("c", O_CLIPPING, "-1");
        new SingleValueOption("q", O_STIME, "1.0");
        new SingleValueOption("s", O_SIGMA, "0.18");

        new SingleValueOption("C", O_COHSIZE, "0.8");
        new SingleValueOption("G", O_SEGSIZE, "3.0");

        new SingleValueOption("D", O_DECODER, "6");

        new SingleValueOption("u", O_LEVEL, "1.0");

        new Flag("t", O_TSTAMPS);
        new SingleValueOption("T", O_TEXT_FORMAT, "L,"+LINE_LENGTH_DEFAULT);

        new Flag("S", O_FPLOT);

        new SingleValueOption("Z", O_PLINT, "0,-1");
        new Flag("A", O_APLOT);
        new Flag("P", O_PPLOT);

        new Flag("Y", O_FSPLOT);

        new SingleValueOption("M", O_HIST_TONE_SPACE, "-1");

        new SteppingOption("v", O_VERBOSE);

        new SingleValueOption("H", O_HIDDEN,
                         "0.002"+                   // dip removal
                        ",0.002"+                   // spike removal
                        ",1"+                       // break too long dashes
                        ",b"+                       // b: Butterworth, cI: Chebyshev I, w: sliding Window, n: No filter
                        ",2.0"+                     // frequency, relative to 1/TU
                        ",2"+                       // filter order
                        ",0"+                       // phase-locked: 0=off, 1=on
                        ",0.8"+                     // phase averaging, relative to TU
                        ",0.75"+                    // merge-dips limit
                        ",0.7"+                     // dip strength min
                        ",0.05"+                    // clipping depth
                        ",0.40"+                    // sliding line half-width .... maybe lower towards 0.30?
                        ",0.35"+                    // spike width max         .... maybe lower towards 0.30?
                        ",0.33"                     // crack width max         .... maybe lower?
                );

        new HelpOption(
                "h",
new String[]{
        "Usage is: bin/gerke-decoder [OPTIONS] WAVEFILE",
        "Options are:",
        String.format("  -o OFFSET          Offset (seconds)"),
        String.format("  -l LENGTH          Length (seconds)"),

        String.format("  -w WPM             WPM, tentative, defaults to %s", GerkeLib.getDefault(O_WPM)),
        String.format("  -W EXPANSION       Expanded spaces: %s", GerkeLib.getDefault(O_SPACE_EXP)),
        String.format("  -F LOW,HIGH        Audio frequency search range, defaults to %s", GerkeLib.getDefault(O_FRANGE)),
        String.format("  -f FREQ            Audio frequency, bypassing search"),
        String.format("  -c CLIPLEVEL       Clipping level, optional"),
        String.format("  -D DECODER         Decoder, defaults to %s (see below)", GerkeLib.getDefault(O_DECODER)),

        String.format("  -u THRESHOLD       Threshold adjustment, defaults to %s", GerkeLib.getDefault(O_LEVEL)),

        String.format("  -q TS_STRETCH      time slice stretch, defaults to %s", GerkeLib.getDefault(O_STIME)),
        String.format("  -s SIGMA           Gaussian sigma, defaults to %s TU", GerkeLib.getDefault(O_SIGMA)),

        String.format("  -C COHERENCE_SIZE  coherence size, defaults to %s TU", GerkeLib.getDefault(O_COHSIZE)),
        String.format("  -G SEGMENT_SIZE    segment size, defaults to %s s", GerkeLib.getDefault(O_SEGSIZE)),

        String.format("  -H PARAMETERS      Experimental parameters, default: %s", GerkeLib.getDefault(O_HIDDEN)),

        String.format("  -S                 Generate frequency spectrum plot"),
        String.format("  -A                 Generate amplitude plot"),
        String.format("  -P                 Generate phase angle plot"),
        String.format("  -Y                 Generate frequency stability plot"),
        String.format("  -M MODES           Generate histograms of tone/space lengths; 1: tone, 0: space"),

        String.format("  -Z START,LENGTH    Time interval for signal and phase plot (seconds)"),
        String.format("  -t                 Insert timestamps in decoded text"),
        String.format("  -T CASE[,LENGTH]   Decoded text case (L/U/C) and line length"),
        String.format("  -v                 Verbosity (may be given several times)"),
        String.format("  -V                 Show version"),
        String.format("  -h                 This help"),
        "",
        "Available decoders are:",
        String.format("  %d    %s",
                DecoderIndex.TONE_SILENCE.ordinal(),
                DECODER_NAME[DecoderIndex.TONE_SILENCE.ordinal()]),
        String.format("  %d    %s",
                DecoderIndex.PATTERN_MATCHING.ordinal(),
                DECODER_NAME[DecoderIndex.PATTERN_MATCHING.ordinal()]),
        String.format("  %d    %s",
                DecoderIndex.DIPS_FINDING.ordinal(),
                DECODER_NAME[DecoderIndex.DIPS_FINDING.ordinal()]),
        String.format("  %d    %s",
                DecoderIndex.LEAST_SQUARES.ordinal(),
                DECODER_NAME[DecoderIndex.LEAST_SQUARES.ordinal()]),
        String.format("  %d    %s",
                DecoderIndex.LSQ2.ordinal(),
                DECODER_NAME[DecoderIndex.LSQ2.ordinal()]),
        String.format("  %d    %s",
                DecoderIndex.LSQ2_PLUS.ordinal(),
                DECODER_NAME[DecoderIndex.LSQ2_PLUS.ordinal()]),
        "",
        "A tentative TU length (length of one dot) is derived from the WPM value",
        "The TU length in ms is taken as = 1200/WPM. This value is used for the",
        "decoding of characters.",
        "",
        "If inter-character and inter-word spaces are extended, provide a factor",
        "greater than 1.0 for EXPANSION.",
        "",
        "The SAMPLE_PERIOD parameter defines the periodicity of signal evaluation",
        "given in TU units.",
        "",
        "The SIGMA parameter defines the width, given in TU units, of the Gaussian",
        "used in computing the signal value."
                });
    }

//    static class Parameters {
//
//        final int frameRate;
//        final int offsetFrames;
//
//        final double tuMillis;
//        final double tsLength;
//        final int framesPerSlice;
//
//        final long wordSpaceLimit;
//        final long charSpaceLimit;
//        final long dashLimit;
//
//        final double plotBegin;
//        final double plotEnd;
//
//        public Parameters(int frameRate, int offsetFrames, double tuMillis, double tsLength, int framesPerSlice,
//                long wordSpaceLimit, long charSpaceLimit, long dashLimit, double plotBegin, double plotEnd) {
//            this.frameRate = frameRate;
//            this.offsetFrames = offsetFrames;
//            this.tuMillis = tuMillis;
//            this.tsLength = tsLength;
//            this.framesPerSlice = framesPerSlice;
//            this.wordSpaceLimit = wordSpaceLimit;
//            this.charSpaceLimit = charSpaceLimit;
//            this.dashLimit = dashLimit;
//            this.plotBegin = plotBegin;
//            this.plotEnd = plotEnd;
//        }
//    }


    public static void main(String[] clArgs) {

        // Ensure that decimal points (not commas) are used
        Locale.setDefault(new Locale("en", "US"));

        try {
            GerkeLib.parseArgs(clArgs);
            showClData();

            // ===================================== Wave file

            final Wav w = new Wav();

            // ===================================== TU and time slice

            final double wpm = GerkeLib.getDoubleOpt(O_WPM);
            final double tuMillis = 1200/wpm;
            new Info("dot time, tentative: %.3f ms", tuMillis);

            // tsLength is the relative TS length.
            // 0.10 is a typical value.
            // TS length in ms is: tsLength*tuMillis
            // Number of TU covered by N time slices is N/(1.0/tsLength) = N*tsLength

            final int decoder = GerkeLib.getIntOpt(O_DECODER);

            final double tsStretch = GerkeLib.getDoubleOpt(O_STIME);
            final double tsLengthGiven = tsStretch*TS_LENGTH[decoder];

            final int framesPerSlice = (int) Math.round(tsLengthGiven*w.frameRate*tuMillis/1000.0);

            final double tsLength = 1000.0*framesPerSlice/(w.frameRate*tuMillis);

            new Info("time slice: %.3f ms", 1000.0*framesPerSlice/w.frameRate);
            new Info("frames per time slice: %d", framesPerSlice);
            new Debug("time slice roundoff: %e", (tsLength - tsLengthGiven)/tsLengthGiven);

            new Info("sigma: %f", GerkeLib.getDoubleOpt(O_SIGMA));


            // ============  Multiply by sine and cosine functions, apply filtering

            // number of slices; this many slices fill the wav array, except for a
            // possible tail that is less than a complete slice
            final int nofSlices = w.nofFrames/framesPerSlice;

            final int fSpecified = GerkeLib.getIntOpt(GerkeDecoder.O_FREQ);



            final CwDetector detector;

            final double decoderThreshold = getThreshold(decoder);

            if (DecoderBase.getDetector(decoder) == DetectorIndex.ADAPTIVE_DETECTOR) {

                // warn if specified frequency .. not expected by this detector

                final double cohSizeGiven = GerkeLib.getDoubleOpt(O_COHSIZE);
                final int cohFactor = (int) Math.round(cohSizeGiven/tsLength);
                final int segFactor = (int) Math.round(
                        GerkeLib.getDoubleOpt(O_SEGSIZE)*w.frameRate/
                        (cohFactor*framesPerSlice));

                detector = new CwAdaptiveImpl(
                        nofSlices,
                        w,
                        tuMillis,
                        framesPerSlice,

                        // TODO, parameter, 4 or 5 seems a reasonable value
                        cohFactor,

                        // TODO, parameter, unclear if it is very critical
                        segFactor,
                        // while trying out, let the product of the two be about 500

                        tsLength
                        );
            }
            else if (DecoderBase.getDetector(decoder) == DetectorIndex.BASIC_DETECTOR) {
                detector = new CwBasicImpl(
                        decoder,
                        decoderThreshold,
                        nofSlices,
                        w,
                        tuMillis,
                        framesPerSlice,
                        tsLength,
                        fSpecified
                        );
            }
            else {
                throw new RuntimeException();
            }



            final Signal signal = detector.getSignal();
            final double[] sig = signal.sig;
            final int sigSize = signal.sig.length;

            if (detector instanceof CwAdaptiveImpl) {
                // diagnostic only
                ((CwAdaptiveImpl)detector).trigTableReport();
            }

            if (GerkeLib.getFlag(O_FSPLOT)) {
                detector.frequencyStabilityPlot();
            }

            if (sigSize != nofSlices) {
                // unexpected ...
                new Info("sigSize: %d, nofSlices: %d", sigSize, nofSlices);
            }

            // ================= Determine floor and ceiling. These arrays are used for
            // decoding and plotting as well.

            final int estBaseCeil = Compute.ensureEven((int)(P_CEIL_HIST_WIDTH*(tuMillis/1000.0)*w.frameRate/framesPerSlice));
            new Debug("ceiling estimation based on slices: %d", estBaseCeil);

            final int estBaseFloor = Compute.ensureEven((int)(P_FLOOR_HIST_WIDTH*(tuMillis/1000.0)*w.frameRate/framesPerSlice));
            new Debug("floor estimation based on slices: %d", estBaseFloor);

            final double[] cei = new double[sig.length];
            final double[] flo = new double[sig.length];

            double ceilingMax = -1.0;
            for (int q = 0; true; q++) {
                if (w.wav.length - q*framesPerSlice < framesPerSlice) {
                    break;
                }

                flo[q] = localFloorByHist(q, sig, estBaseFloor, tsLength);
                cei[q] = localCeilByHist(q, sig, estBaseCeil, tsLength);
                ceilingMax = Compute.dMax(ceilingMax, cei[q]);
            }

            // some of this is used by the phase plot

            final int dashLimit = (int) Math.round(DASH_LIMIT[decoder]*tuMillis*w.frameRate/(1000*framesPerSlice));        // PARAMETER
            final int wordSpaceLimit = (int) Math.round(WORD_SPACE_LIMIT[decoder]*tuMillis*w.frameRate/(1000*framesPerSlice));   // PARAMETER
//            final int charSpaceLimit = (int) Math.round(CHAR_SPACE_LIMIT[decoder]*tuMillis*w.frameRate/(1000*framesPerSlice));   // PARAMETER
//            final int twoDashLimit = (int) Math.round(TWO_DASH_LIMIT*tuMillis*w.frameRate/(1000*framesPerSlice));     // PARAMETER
            final double level = GerkeLib.getDoubleOpt(O_LEVEL);

            new Info("relative tone/silence threshold: %.3f", level);
            new Debug("dash limit: %d, word space limit: %d", dashLimit, wordSpaceLimit);

            // ================ Phase plot, optional

            if (GerkeLib.getFlag(O_PPLOT)) {

                detector.phasePlot(
                        sig,
                        level,
                        flo,
                        cei);
            }

            final int offset = GerkeLib.getIntOpt(O_OFFSET);
            final Formatter formatter = new Formatter();
            final PlotEntries plotEntries =
                    GerkeLib.getFlag(O_APLOT) ? new PlotEntries(w) : null;

            if (plotEntries != null) {
                for (int q = 0; q < sig.length; q++) {
                    final double seconds = w.secondsFromSliceIndex(q, framesPerSlice);
                    if (plotEntries.plotBegin <= seconds && seconds <= plotEntries.plotEnd) {
                        final double threshold = detector.threshold(level, flo[q], cei[q], decoder);
                        plotEntries.addAmplitudes(seconds, sig[q], threshold, cei[q], flo[q]);
                    }
                }
            }

            /**
             * The array may contain 1 (tones histogram) or 0 (spaces histogram) or both.
             */
            final int[] histRequests = GerkeLib.getIntOptMulti(O_HIST_TONE_SPACE);
            if (histRequests.length > 0 && decoder != DecoderIndex.LSQ2_PLUS.ordinal()) {
                new Death("Option -%s only supported for -%s %d",
                        GerkeLib.getOptShortName(O_HIST_TONE_SPACE),
                        GerkeLib.getOptShortName(O_DECODER),
                        DecoderIndex.LSQ2_PLUS.ordinal());
            }

            final HistEntries histEntries;
            if (GerkeLib.member(1, histRequests) ||
                    GerkeLib.member(0, histRequests)) { // move method to GerkeLib? TODO
                if (! GerkeLib.validBoundaries(0, 1, histRequests)) {
                    new Death("Option -%s values must be 0 or 1",
                            GerkeLib.getOptShortName(O_HIST_TONE_SPACE));
                }
                histEntries = new HistEntries(tuMillis, tsLength);
            }
            else {
                histEntries = null;
            }

            new Info("decoder: %s (%d)", DECODER_NAME[decoder], decoder);
            final Decoder dec;
            if (decoder == DecoderIndex.TONE_SILENCE.ordinal()) {
                dec = new ToneSilenceDecoder(
                        tuMillis,
                        framesPerSlice,
                        tsLength,
                        offset,
                        w,
                        sig,
                        plotEntries,
                        formatter,

//                        trans,
//                        transIndex,
                        ceilingMax,

                        nofSlices,
                        level,
                        cei,
                        flo

                        );

            }

            else if (decoder == DecoderIndex.PATTERN_MATCHING.ordinal()) {
                dec = new PatternMatchDecoder(

                        tuMillis,
                        framesPerSlice,
                        tsLength,
                        offset,
                        w,
                        sig,
                        plotEntries,
                        formatter,

                        ceilingMax,
//                        trans,
//                        transIndex,
                        level,

                        nofSlices,
                        cei,
                        flo);
            }

            else if (decoder == DecoderIndex.DIPS_FINDING.ordinal()) {
                dec = new DipsFindingDecoder(
                        tuMillis,
                        framesPerSlice,
                        tsLength,
                        offset,
                        w,
                        sig,
                        plotEntries,
                        formatter,

                        ceilingMax,
//                        trans,
//                        transIndex,
                        cei,
                        flo,
                        nofSlices,
                        level
                        );
            }

            else if (decoder == DecoderIndex.LEAST_SQUARES.ordinal()) {
                dec = new LeastSquaresDecoder(

                        tuMillis,
                        framesPerSlice,
                        tsLength,
                        offset,
                        w,
                        sig,
                        plotEntries,
                        formatter,

                        sigSize,
                        cei,
                        flo,
                        ceilingMax

                        );
            }
            else if (decoder == DecoderIndex.LSQ2.ordinal()) {
                dec = new SlidingLineDecoder(
                        tuMillis,
                        framesPerSlice,
                        tsLength,
                        offset,
                        w,
                        sig,
                        plotEntries,
                        formatter,

                        sigSize,
                        cei,
                        flo,
                        level,
                        ceilingMax

                        );
            }
            else if (decoder == DecoderIndex.LSQ2_PLUS.ordinal()) {
                dec = new SlidingLinePlus(
                        tuMillis,
                        framesPerSlice,
                        tsLength,
                        offset,
                        w,
                        sig,
                        plotEntries,
                        histEntries,
                        formatter,

                        sigSize,
                        cei,
                        flo,
                        level,
                        ceilingMax

                        );
            }
            else {
                dec = null;
                new Death("no such decoder: '%d'", decoder);
            }
            dec.execute();

            new Info("decoded text MD5 digest: %s", formatter.getDigest());

            if (GerkeLib.member(1, histRequests)) {
                final HistCollector hc =
                        new HistCollector(
                                histEntries.binWidth,
                                histEntries.getVerticalRange(1),
                                histEntries.getHorisontalRange(1));
                for (Double d : histEntries.widthsOfTones) {
                    hc.ps.println(String.format("%f", d));
                }
                hc.plot(1);
            }

            if (GerkeLib.member(0, histRequests)) {
                final HistCollector hc =
                        new HistCollector(
                                histEntries.binWidth,
                                histEntries.getVerticalRange(0),
                                histEntries.getHorisontalRange(0));
                for (Double d : histEntries.widthsOfSpaces) {
                    hc.ps.println(String.format("%f", d));
                }
                hc.plot(0);
            }

            if (plotEntries != null) {
                final PlotCollector pc = new PlotCollector();
                double initDigitizedSignal = -1.0;
                double signa = 0.0;
                double thresha = 0.0;
                double ceiling = 0.0;
                double floor = 0.0;
                double sigavg = 0.0;
                double digitizedSignal = initDigitizedSignal;

                final boolean hasSigPlus = plotEntries.hasSigPlus();

                for (Entry<Double, List<PlotEntryBase>> e : plotEntries.entries.entrySet()) {

                    for (PlotEntryBase peb : e.getValue()) {
                        if (peb instanceof PlotEntryDecode) {
                            digitizedSignal = ((PlotEntryDecode)peb).dec;
                        }
                        else if (peb instanceof PlotEntrySigPlus) {
                            signa = ((PlotEntrySigPlus)peb).sig;
                            thresha = ((PlotEntrySigPlus)peb).threshold;
                            ceiling = ((PlotEntrySigPlus)peb).ceiling;
                            floor = ((PlotEntrySigPlus)peb).floor;
                            sigavg = ((PlotEntrySigPlus)peb).sigAvg;

                        }
                        else if (peb instanceof PlotEntrySig) {
                            signa = ((PlotEntrySig)peb).sig;
                            thresha = ((PlotEntrySig)peb).threshold;
                            ceiling = ((PlotEntrySig)peb).ceiling;
                            floor = ((PlotEntrySig)peb).floor;
                        }
                    }

                    if (hasSigPlus) {
                        pc.ps.println(String.format("%f %f %f %f %f %f %f",
                                e.getKey().doubleValue(),
                                signa,
                                sigavg,
                                thresha,
                                ceiling,
                                floor,
                                digitizedSignal));
                    }
                    else {
                        pc.ps.println(String.format("%f %f %f %f %f %f",
                                e.getKey().doubleValue(),
                                signa,
                                thresha,
                                ceiling,
                                floor,
                                digitizedSignal));
                    }
                }

                if (hasSigPlus) {
                    pc.plot(new Mode[] {
                            Mode.LINES_PURPLE,
                            Mode.LINES_RED,
                            Mode.LINES_CYAN,
                            Mode.LINES_GREEN,
                            Mode.LINES_GREEN,
                            Mode.LINES_CYAN});
                }
                else {
                    pc.plot(new Mode[] {
                            Mode.LINES_PURPLE,
                            Mode.LINES_RED,
                            Mode.LINES_GREEN,
                            Mode.LINES_GREEN,
                            Mode.LINES_CYAN});
                }
            }

        }
        catch (Exception e) {
            new Death(e);
        }

        if (CollectorBase.windowsPlotCount > 0) {
            GerkeLib.prompt("push Enter to exit this program: ");
        }
    }

    /**
     * TODO, consider using static array lookup?
     * @param decoder
     * @return
     */
    public static double getThreshold(int decoder) {

        if (decoder == 1) {
            return ToneSilenceDecoder.THRESHOLD;
        }
        else if (decoder == 2) {
            return PatternMatchDecoder.THRESHOLD;
        }
        else if (decoder == 3) {
            return DipsFindingDecoder.THRESHOLD;
        }
        else if (decoder == 4) {
            return LeastSquaresDecoder.THRESHOLD;
        }
        else if (decoder == 5) {
            return SlidingLineDecoder.THRESHOLD;
        }
        else if (decoder == 6) {
            return SlidingLinePlus.THRESHOLD;
        }
        else {
            throw new RuntimeException();
        }
}





    /**
     * Returns time in seconds, relative to beginning of wave file.
     *
     * @param q
     * @param framesPerSlice
     * @param frameRate
     * @param offsetFrames
     * @return
     */
    public static double timeSeconds(int q, int framesPerSlice, int frameRate, int offsetFrames) {
        return (((double) q)*framesPerSlice + offsetFrames)/frameRate;
    }

//    /**
//     * Remove null elements from the given array. The returned value
//     * is the new active size.
//     *
//     * @param trans
//     * @param activeSize
//     * @return
//     */
//    private static int removeHoles(Trans[] trans, int activeSize) {
//        int k = 0;
//        for (int i = 0; i < activeSize; i++) {
//            if (trans[i] != null) {
//                trans[k] = trans[i];
//                k++;
//            }
//        }
//        return k;
//    }


    private static void showClData() {
        if (GerkeLib.getIntOpt(O_VERBOSE) >= 2) {
            new Info("version: %s", GerkeLib.getOpt(O_VERSION));
            new Info("WPM, tentative: %f", GerkeLib.getDoubleOpt(O_WPM));
            new Info("frequency: %d", GerkeLib.getIntOpt(O_FREQ));
            new Info("f0,f1: %s", GerkeLib.getOpt(O_FRANGE));
            new Info("offset: %d", GerkeLib.getIntOpt(O_OFFSET));
            new Info("length: %d", GerkeLib.getIntOpt(O_LENGTH));
            new Info("clipLevel: %d", GerkeLib.getIntOpt(O_CLIPPING));

            new Info("sampling period: %f", GerkeLib.getDoubleOpt(O_STIME));
            new Info("sigma: %f", GerkeLib.getDoubleOpt(O_SIGMA));

            new Info("level: %f", GerkeLib.getDoubleOpt(O_LEVEL));
            new Info("frequency plot: %b", GerkeLib.getFlag(O_FPLOT));
            new Info("signal plot: %b", GerkeLib.getFlag(O_APLOT));
            new Info("phase plot: %b", GerkeLib.getFlag(O_PPLOT));
            new Info("plot interval: %s", GerkeLib.getOpt(O_PLINT));
            new Info("timestamps: %b", GerkeLib.getFlag(O_TSTAMPS));
            new Info("hidden: %s", GerkeLib.getOpt(O_HIDDEN));
            new Info("verbose: %d", GerkeLib.getIntOpt(O_VERBOSE));

            for (int k = 0; k < GerkeLib.nofArguments(); k++) {
                new Info("argument %d: %s", k+1, GerkeLib.getArgument(k));
            }
        }
    }

    private static double localCeilByHist(int q, double[] sig, int width, double tsLength) {

        final int q1 = Compute.iMax(q-width/2, 0);
        final int q2 = Compute.iMin(q+width/2, sig.length);

        final double tsLengthDefault = Double.parseDouble(GerkeDecoder.STIME_DEFAULT);
        final int largeInt = 500;    // not critical, not a parameter

        // find the maximal sig value
        double sigMax = -1.0;
        for (int j = q1; j < q2; j++) {
            sigMax = Compute.dMax(sig[j], sigMax);
        }

        // produce a histogram with P_CEIL_HIST slots
        int sumPoints = 0;
        final int[] hist = new int[P_CEIL_HIST];
        for (int j = q1; j < q2; j++) {
            final int index = (int) Math.round((P_CEIL_HIST-1)*sig[j]/sigMax);
            int points = (int)Math.round(largeInt/(1 + Compute.squared((j-q)*(tsLength/tsLengthDefault)/P_CEIL_FOCUS)));
            hist[index] += points;
            sumPoints += points;
        }

        // remove the low-amp counts
        int count = (int) Math.round(P_CEIL_FRAC*sumPoints);
        int kCleared = -1;
        for (int k = 0; k < P_CEIL_HIST; k++) {
            final int decr = Compute.iMin(hist[k], count);
            hist[k] -= decr;
            count -= decr;
            if (hist[k] == 0)  {
                kCleared = k;
            }
            if (count == 0) {
                break;
            }
        }

        // now produce a weighted average
        int sumCount = 0;
        double sumAmp = 0.0;
        for (int k = kCleared+1; k < P_CEIL_HIST; k++) {
            sumCount += hist[k];
            sumAmp += hist[k]*k*sigMax/P_CEIL_HIST;
        }
        return sumAmp/sumCount;
    }

    /**
     * Find a localized floor level by building a histogram and using
     * the low-amplitude values. The given width is trusted even.
     *
     * @param q
     * @param sig
     * @param width
     * @param tsLength
     * @return
     */
    private static double localFloorByHist(int q, double[] sig, int width, double tsLength) {

        final int q1 = Compute.iMax(q-width/2, 0);
        final int q2 = Compute.iMin(q+width/2, sig.length);

        final double tsLengthDefault = Double.parseDouble(GerkeDecoder.STIME_DEFAULT);
        final int largeInt = 500;    // not critical, not a parameter

        // find the maximum amplitude
        double sigMax = -1.0;
        for (int j = q1; j < q2; j++) {
            sigMax = Compute.dMax(sig[j], sigMax);
        }

        // produce a histogram with P_FLOOR_HIST slots
        int sumPoints = 0;
        final int[] hist = new int[P_FLOOR_HIST];
        for (int j = q1; j < q2; j++) {
            final int index = (int) Math.round((P_FLOOR_HIST-1)*sig[j]/sigMax);
            int points = (int)Math.round(largeInt/(1 + Compute.squared((j-q)*(tsLength/tsLengthDefault)/P_FLOOR_FOCUS)));
            hist[index] += points;
            sumPoints += points;
        }

        // remove the high-signal counts
        int kCleared = P_FLOOR_HIST;
        int count = (int) Math.round(P_FLOOR_FRAC*sumPoints);
        for (int k = P_FLOOR_HIST-1; k >= 0; k--) {
            final int decr = Compute.iMin(hist[k], count);
            hist[k] -= decr;
            if (hist[k] == 0) {
                kCleared = k;
            }
            count -= decr;
            if (count == 0) {
                break;
            }
        }

        // now produce a weighted average
        int sumCount = 0;
        double sumAmp = 0.0;

        for (int k = 0; k < kCleared; k++) {
            sumCount += hist[k];
            sumAmp += hist[k]*k*sigMax/P_FLOOR_HIST;
        }

        return sumAmp/sumCount;
    }
}
