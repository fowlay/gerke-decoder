package st.foglo.gerke_decoder;

// gerke-decoder - translates Morse code audio to text
//
// Copyright (C) 2020-2022 Rabbe Fogelholm
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

import java.io.IOException;
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
import st.foglo.gerke_decoder.decoder.tone_silence.ToneSilenceDecoder;
import st.foglo.gerke_decoder.detector.CwDetector;
import st.foglo.gerke_decoder.detector.Signal;
import st.foglo.gerke_decoder.detector.adaptive.CwAdaptiveImpl;
import st.foglo.gerke_decoder.detector.cw_basic.CwBasicImpl;
import st.foglo.gerke_decoder.format.Formatter;
import st.foglo.gerke_decoder.lib.Compute;
import st.foglo.gerke_decoder.plot.PlotCollector;
import st.foglo.gerke_decoder.plot.PlotCollector.Mode;
import st.foglo.gerke_decoder.plot.PlotEntries;
import st.foglo.gerke_decoder.plot.PlotEntryBase;
import st.foglo.gerke_decoder.plot.PlotEntryDecode;
import st.foglo.gerke_decoder.plot.PlotEntrySig;
import st.foglo.gerke_decoder.wave.Wav;

public final class GerkeDecoder {




    static final double IGNORE = 0.0;

    /**
     * Tone/silence threshold per decoder. Values were determined by
     * decoding some recordings with good signal strength.
     */
    public static final double[] THRESHOLD = {IGNORE,
            0.524, 0.64791, 0.524, 0.524, 0.524*0.9};

    /**
     * For use when logarithmic mapping is in effect. The encoded
     * value is the logarithm of the threshold value, expressed as
     * a fraction of the ceiling level.
     */
    public static final double[] THRESHOLD_BY_LOG = {IGNORE,
            Math.log(0.54), Math.log(0.54), Math.log(0.54), Math.log(0.54), Math.log(0.677)};

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
    static final String O_AMP_MAPPING = "amp-mapping";
    static final String O_LEVEL = "level";
    public static final String O_TSTAMPS = "timestamps";
    public static final String O_FPLOT = "frequency-plot";
    static final String O_PLINT = "plot-interval";
    static final String O_PLOT = "plot";
    static final String O_PPLOT = "phase-plot";
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
        DIP_STRENGTH_MIN
    };

    static final String[] DECODER_NAME = new String[] {"",
    		"tone/silence",
    		"pattern matching",
    		"dips finding",
    		"least squares",
    		"lsq2"};
    
    /**
     * Numeric decoder index 1..5 maps to these names. Do not reorder.
     * @author erarafo
     *
     */
    public enum DecoderIndex {
    	ZERO,
        TONE_SILENCE,
        PATTERN_MATCHING,
        DIPS_FINDING,
        LEAST_SQUARES,
        LSQ2
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
    public static final double[] WORD_SPACE_LIMIT = new double[]{IGNORE, 5.0, 5.0, 5.0, 5.5, 5.6};

    /**
     * Pauses longer than this denote a character boundary. Unit is TU.
     * Model value: sqrt(1*3) = 1.73
     * chars break <---------+---------> chars cluster
     */
    public static final double[] CHAR_SPACE_LIMIT = new double[]{IGNORE, 1.65, 1.65, 1.73, 2.1, 1.9};  // 2.3??

    /**
     * Tones longer than this are interpreted as a dash
     * Model value: sqrt(1*3) = 1.73
     * too many dashes <---------+---------> too few dashes
     */
    public static final double[] DASH_LIMIT = new double[]{IGNORE, 1.8, 1.73, 1.7, 1.7, 1.9};

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


    static {
        /**
         * Note: Align with the top level pom.xml. Also update the
         * version history in README.md. Also update the DEVELOPERS_NOTES file.
         */
        new VersionOption("V", O_VERSION, "gerke-decoder version 2.1.1.1");

        new SingleValueOption("o", O_OFFSET, "0");
        new SingleValueOption("l", O_LENGTH, "-1");

        new SingleValueOption("F", O_FRANGE, "400,1200");
        new SingleValueOption("f", O_FREQ, "-1");

        new SingleValueOption("w", O_WPM, "15.0");
        new SingleValueOption("W", O_SPACE_EXP, "1.0");

        new SingleValueOption("c", O_CLIPPING, "-1");
        new SingleValueOption("q", O_STIME, STIME_DEFAULT);
        new SingleValueOption("s", O_SIGMA, "0.18");
        
        new SingleValueOption("C", O_COHSIZE, "0.8");
        new SingleValueOption("G", O_SEGSIZE, "3.0");

        new SingleValueOption("D", O_DECODER, "5");

        new SingleValueOption("U", O_AMP_MAPPING, "1");

        new SingleValueOption("u", O_LEVEL, "1.0");

        new Flag("t", O_TSTAMPS);

        new Flag("S", O_FPLOT);

        new SingleValueOption("Z", O_PLINT, "0,-1");
        new Flag("A", O_PLOT);
        new Flag("P", O_PPLOT);

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
                        ",0.7"                      // dip strength min                       
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
        String.format("  -D DECODER         1: %s, 2: %s, 3: %s, 4: %s, defaults to %s",
        		DECODER_NAME[DecoderIndex.TONE_SILENCE.ordinal()],
        		DECODER_NAME[DecoderIndex.PATTERN_MATCHING.ordinal()],
        		DECODER_NAME[DecoderIndex.DIPS_FINDING.ordinal()],
        		DECODER_NAME[DecoderIndex.LEAST_SQUARES.ordinal()],
        		DECODER_NAME[DecoderIndex.LSQ2.ordinal()],
                GerkeLib.getDefault(O_DECODER)),
        String.format("  -U MAPPING         1: none, 2: square root, 3: logarithm, defaults to: %d", GerkeLib.getIntOpt(O_AMP_MAPPING)),
        String.format("  -u THRESHOLD       Threshold adjustment, defaults to %s", GerkeLib.getDefault(O_LEVEL)),

        String.format("  -q SAMPLE_PERIOD   sample period, defaults to %s TU", GerkeLib.getDefault(O_STIME)),
        String.format("  -s SIGMA           Gaussian sigma, defaults to %s TU", GerkeLib.getDefault(O_SIGMA)),
        
        String.format("  -C COHERENCE_SIZE  coherence size, defaults to %s TU", GerkeLib.getDefault(O_COHSIZE)),
        String.format("  -G SEGMENT_SIZE    segment size, defaults to %s s", GerkeLib.getDefault(O_SEGSIZE)),

        String.format("  -H PARAMETERS      Experimental parameters, default: %s", GerkeLib.getDefault(O_HIDDEN)),

        String.format("  -S                 Generate frequency spectrum plot"),
        String.format("  -A                 Generate amplitude plot"),
        String.format("  -P                 Generate phase angle plot"),

        String.format("  -Z START,LENGTH    Time interval for signal and phase plot (seconds)"),
        String.format("  -t                 Insert timestamps in decoded text"),
        String.format("  -v                 Verbosity (may be given several times)"),
        String.format("  -V                 Show version"),
        String.format("  -h                 This help"),
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
            final double[] plotLimits = getPlotLimits(w);


            // ===================================== TU and time slice

            final double wpm = GerkeLib.getDoubleOpt(O_WPM);
            final double tuMillis = 1200/wpm;
            new Info("dot time, tentative: %.3f ms", tuMillis);

            // tsLength is the relative TS length.
            // 0.10 is a typical value.
            // TS length in ms is: tsLength*tuMillis
            // Number of TU covered by N time slices is N/(1.0/tsLength) = N*tsLength

            final double tsLengthGiven = GerkeLib.getDoubleOpt(O_STIME);

            final int framesPerSlice = (int) Math.round(tsLengthGiven*w.frameRate*tuMillis/1000.0);

            final double tsLength = 1000.0*framesPerSlice/(w.frameRate*tuMillis);

            new Info("time slice: %.3f ms", 1000.0*framesPerSlice/w.frameRate);
            new Info("frames per time slice: %d", framesPerSlice);
            new Debug("time slice roundoff: %e", (tsLength - tsLengthGiven)/tsLengthGiven);
            
            new Info("sigma: %f", GerkeLib.getDoubleOpt(O_SIGMA));


            // ============  Multiply by sine and cosine functions, apply filtering

            final int nofSlices = w.nofFrames/framesPerSlice;

            final int fSpecified = GerkeLib.getIntOpt(GerkeDecoder.O_FREQ);
            
            final int decoder = GerkeLib.getIntOpt(O_DECODER);
            
            final CwDetector detector;
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
            	((CwAdaptiveImpl)detector).trigTableReport();
            }

            final int ampMap = GerkeLib.getIntOpt(O_AMP_MAPPING);
            applyAmplitudeMapping(sig, sigSize, ampMap);

            if (sigSize != nofSlices) {
                // unexpected ...
                new Info("sigSize: %d, nofSlices: %d", sigSize, nofSlices);
            }

            // ================= Determine floor and ceiling. These arrays are used for
            // decoding and plotting as well.

            //
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
            final double levelLog = Math.log(level);

            new Info("relative tone/silence threshold: %.3f", level);
            new Debug("dash limit: %d, word space limit: %d", dashLimit, wordSpaceLimit);

            // ================ Phase plot, optional
            // not supported by all detectors

            if (GerkeLib.getFlag(O_PPLOT)) {
            	if (signal.fBest == 0) {
            		new Warning("phase plot not supported by this decoder");
            	}
            	else {
            		phasePlot(signal.fBest, nofSlices, framesPerSlice,
            				w, signal.clipLevel,
            				sig, level, levelLog,
            				flo, cei, decoder, ampMap);
            	}
            }

            final int offset = GerkeLib.getIntOpt(O_OFFSET);
            final Formatter formatter = new Formatter();
            final PlotEntries plotEntries =
                    GerkeLib.getFlag(O_PLOT) ? new PlotEntries() : null;

            if (plotEntries != null) {
                for (int q = 0; q < sig.length; q++) {
                    final double seconds = timeSeconds(q, framesPerSlice, w.frameRate, w.offsetFrames);
                    if (plotLimits[0] <= seconds && seconds <= plotLimits[1]) {
                        final double threshold = threshold(decoder, ampMap, level, levelLog, flo[q], cei[q]);
                        plotEntries.addAmplitudes(seconds, sig[q], threshold, cei[q], flo[q]);
                    }
                }
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
            			plotLimits,
            			formatter,
            			
//            			trans,
//            			transIndex,
            			ceilingMax,
            			
            			nofSlices,
            			ampMap,
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
            			plotLimits,
            			formatter,
            			
            			ceilingMax,
//            			trans,
//            			transIndex,
            			ampMap,
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
            			plotLimits,
            			formatter,
            			
            			ceilingMax,
//            			trans,
//            			transIndex,
            			cei,
            			flo,
            			nofSlices,
            			ampMap,
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
            			plotLimits,
            			formatter,
            			
            			sigSize,
            			cei,
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
            			plotLimits,
            			formatter,
            			
            			sigSize,
            			ampMap,
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

            if (plotEntries != null) {
                final PlotCollector pc = new PlotCollector();
                double initDigitizedSignal = -1.0;
                double signa = 0.0;
                double thresha = 0.0;
                double ceiling = 0.0;
                double floor = 0.0;
                double digitizedSignal = initDigitizedSignal;
                for (Entry<Double, List<PlotEntryBase>> e : plotEntries.entries.entrySet()) {

                    for (PlotEntryBase peb : e.getValue()) {
                        if (peb instanceof PlotEntryDecode) {
                            digitizedSignal = ((PlotEntryDecode)peb).dec;
                        }
                        else if (peb instanceof PlotEntrySig) {
                            signa = ((PlotEntrySig)peb).sig;
                            thresha = ((PlotEntrySig)peb).threshold;
                            ceiling = ((PlotEntrySig)peb).ceiling;
                            floor = ((PlotEntrySig)peb).floor;
                        }
                    }

                    pc.ps.println(String.format("%f %f %f %f %f %f",
                            e.getKey().doubleValue(), signa, thresha, ceiling, floor, digitizedSignal));
                }

                pc.plot(new Mode[] {Mode.LINES_PURPLE, Mode.LINES_RED, Mode.LINES_GREEN, Mode.LINES_GREEN, Mode.LINES_CYAN}, 5);
            }

        }
        catch (Exception e) {
            new Death(e);
        }
    }

    private static void applyAmplitudeMapping(double[] sig, int size, int ampMap) {

        if (ampMap == 3) {
            new Info("apply logarithmic mapping");
            double sigMax = 0.0;
            for (int k = 0; k < size; k++) {
                sigMax = Compute.dMax(sigMax, sig[k]);
            }
            if (!(sigMax > 0.0)) {
                new Death("no data, cannot apply logarithmic mapping");
            }
            double sigMin = Double.MAX_VALUE;
            for (int k = 0; k < size; k++) {
                sig[k] = Math.log(sig[k] + sigMax/100);
                sigMin = Compute.dMin(sigMin, sig[k]);
            }
            for (int k = 0; k < size; k++) {
                sig[k] = sig[k] - sigMin;
            }
        }
        else if (ampMap == 2) {
            new Info("");
            for (int k = 0; k < size; k++) {
                sig[k] = Math.sqrt(sig[k]);
            }
        }
        else if (ampMap == 1) {
            new Info("no amplitude mapping");
        }
        else {
            new Death("invalid amplitude mapping: %d", ampMap);
        }
    }


    /**
     * Determines threshold based on decoder and amplitude mapping.
     */
    private static double threshold(
            int decoder,
            int ampMap,
            double level,
            double levelLog,
            double floor,
            double ceiling) {

        if (ampMap == 3) {
        	// logarithmic mapping, floor is ignored
            return ceiling + THRESHOLD_BY_LOG[decoder] + levelLog;
        }
        else if (ampMap == 2 || ampMap == 1) {
            return floor + level*THRESHOLD[decoder]*(ceiling - floor);
        }
        else {
            new Death("invalid amplitude mapping: %d", ampMap);
            return 0.0;
        }
    }

    private static void phasePlot(int fBest, int nofSlices, int framesPerSlice, Wav w,
            int clipLevel, double[] sig, double level, double levelLog,
            double[] flo, double[] cei,
            int decoder,
            int ampMap) throws IOException, InterruptedException {
        final boolean phasePlot = GerkeLib.getFlag(O_PPLOT);
        final double[] plotLimits = getPlotLimits(w);


        final double[] cosSum = new double[nofSlices];
        final double[] sinSum = new double[nofSlices];
        final double[] wphi = new double[nofSlices];

        for (int q = 0; true; q++) {

            if (w.wav.length - q*framesPerSlice < framesPerSlice) {
                break;
            }

            final double angleOffset =
                    phasePlot ? Compute.TWO_PI*fBest*timeSeconds(q, framesPerSlice, w.frameRate, w.offsetFrames) : 0.0;

                    double sinAcc = 0.0;
                    double cosAcc = 0.0;
                    for (int j = 0; j < framesPerSlice; j++) {
                        // j is frame index
                        final int ampRaw = w.wav[q*framesPerSlice + j];
                        final int amp = ampRaw < 0 ?
                                Compute.iMax(-clipLevel, ampRaw) :
                                    Compute.iMin(clipLevel, ampRaw);

                                final double angle = angleOffset + Compute.TWO_PI*fBest*j/w.frameRate;
                                sinAcc += Math.sin(angle)*amp;
                                cosAcc += Math.cos(angle)*amp;
                    }
                    cosSum[q] = cosAcc;
                    sinSum[q] = sinAcc;
        }

        for (int q = 0; q < wphi.length; q++) {
            wphi[q] = wphi(q, cosSum, sinSum, sig, level, levelLog, flo, cei, decoder, ampMap);
        }

        final PlotCollector pcPhase = new PlotCollector();
        for (int q = 0; q < wphi.length; q++) {
            final double seconds = timeSeconds(q, framesPerSlice, w.frameRate, w.offsetFrames);
            if (plotLimits[0] <= seconds && seconds <= plotLimits[1]) {
                final double phase = wphi[q];
                if (phase != 0.0) {
                    pcPhase.ps.println(String.format("%f %f", seconds, phase));
                }
            }
        }
        pcPhase.plot(new Mode[] {Mode.POINTS}, 1);
    }

    private static double[] getPlotLimits(Wav w) {

        final double[] result = new double[2];

        if (GerkeLib.getFlag(O_PLOT) || GerkeLib.getFlag(O_PPLOT)) {

            if (GerkeLib.getOptMultiLength(O_PLINT) != 2) {
                new Death("bad plot interval: wrong number of suboptions");
            }

            final double t1 = ((double) w.frameLength)/w.frameRate;

            final double t2 = (double) (GerkeLib.getIntOpt(O_OFFSET));

            final double t3 =
                    w.length == -1 ? t1 :
                        Compute.dMin(t2 + w.length, t1);
            if (w.length != -1 && t2 + w.length > t1) {
                new Warning("offset+length exceeds %.1f seconds", t1);
            }

            final double t4 =
                    Compute.dMax(GerkeLib.getDoubleOptMulti(O_PLINT)[0], t2);
            if (t4 >= t3) {
                new Death("plot interval out of bounds");
            }
            else if (GerkeLib.getDoubleOptMulti(O_PLINT)[0] < t2) {
                new Warning("starting plot interval at: %.1f s", t2);
            }

            final double t5 =
                    GerkeLib.getDoubleOptMulti(O_PLINT)[1] == -1.0 ?
                            t3 :
                                Compute.dMin(t3, t4 + GerkeLib.getDoubleOptMulti(O_PLINT)[1]);
            if (GerkeLib.getDoubleOptMulti(O_PLINT)[1] != -1.0 &&
                    t4 + GerkeLib.getDoubleOptMulti(O_PLINT)[1] > t3) {
                new Warning("ending plot interval at: %.1f s", t3);
            }

            result[0] = t4;
            result[1] = t5;
            if (result[0] >= result[1]) {
                new Death("bad plot interval");
            }
        }
        return result;
    }



    private static double timeSeconds(int q, int framesPerSlice, int frameRate, int offsetFrames) {
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

    /**
     * Weighted computation of phase angle. Returns 0.0 if there is no tone.
     *
     * @param k
     * @param x
     * @param y
     * @param sig
     * @return
     */
    private static double wphi(
            int k,
            double[] x,
            double[] y,
            double[] sig,
            double level,
            double levelLog,
            double[] flo,
            double[] cei,
            int decoder,
            int ampMap) {

        final int len = sig.length;
        final int width = 7;

        double sumx = 0.0;
        double sumy = 0.0;
        double ampAve = 0.0;
        int m = 0;
        for (int j = Compute.iMax(0, k-width); j <= Compute.iMin(len-1, k+width); j++) {
            final double amp = sig[j];
            ampAve += amp;
            m++;
            sumx += amp*amp*x[j];
            sumy += amp*amp*y[j];
        }
        ampAve = ampAve/m;

        // TODO, revise for logarithmic case?
        if (ampAve < threshold(decoder, ampMap, 0.2*level, levelLog, flo[k], cei[k])) {
            return 0.0;
        }
        else {
            return Math.atan2(sumy, sumx);
        }
    }


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
            new Info("signal plot: %b", GerkeLib.getFlag(O_PLOT));
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
