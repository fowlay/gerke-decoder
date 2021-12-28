package st.foglo.gerke_decoder;

// gerke-decoder - translates Morse code audio to text
//
// Copyright (C) 2020-2021 Rabbe Fogelholm
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
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.NavigableMap;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

import st.foglo.gerke_decoder.GerkeLib.*;
import st.foglo.gerke_decoder.detector.CwDetector;
import st.foglo.gerke_decoder.detector.Signal;
import st.foglo.gerke_decoder.detector.cw_basic.CwBasicImpl;
import st.foglo.gerke_decoder.lib.Compute;
import st.foglo.gerke_decoder.plot.PlotCollector;
import st.foglo.gerke_decoder.plot.PlotCollector.Mode;
import st.foglo.gerke_decoder.plot.PlotEntries;
import st.foglo.gerke_decoder.plot.PlotEntryBase;
import st.foglo.gerke_decoder.plot.PlotEntryDecode;
import st.foglo.gerke_decoder.plot.PlotEntrySig;
import st.foglo.gerke_decoder.wave.Wav;

public final class GerkeDecoder {

    static final int HI = 10;
    static final int LO = -35;

    public static final double TWO_PI = 2*Math.PI;

    static final double IGNORE = 0.0;

    /**
     * Tone/silence threshold per decoder. Values were determined by
     * decoding some recordings with good signal strength.
     */
    static final double[] THRESHOLD = {IGNORE,
            0.524, 0.64791, 0.524, 0.524};

    /**
     * For use when logarithmic mapping is in effect. The encoded
     * value is the logarithm of the threshold value, expressed as
     * a fraction of the ceiling level.
     */
    static final double[] THRESHOLD_BY_LOG = {IGNORE,
            Math.log(0.54), Math.log(0.54), Math.log(0.54), Math.log(0.54), Math.log(0.677)};

    static final String O_VERSION = "version";
    public static final String O_OFFSET = "offset";
    public static final String O_LENGTH = "length";
    public static final String O_FRANGE = "freq-range";
    public static final String O_FREQ = "freq";
    static final String O_WPM = "wpm";
    static final String O_SPACE_EXP = "space-expansion";
    public static final String O_CLIPPING = "clip";
    static final String O_STIME = "sample-time";
    public static final String O_SIGMA = "sigma";
    static final String O_DECODER = "decoder";
    static final String O_AMP_MAPPING = "amp-mapping";
    static final String O_LEVEL = "level";
    static final String O_TSTAMPS = "timestamps";
    public static final String O_FPLOT = "frequency-plot";
    static final String O_PLINT = "plot-interval";
    static final String O_PLOT = "plot";
    static final String O_PPLOT = "phase-plot";
    static final String O_VERBOSE = "verbose";

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
    
    enum decoderIndex {
    	ZERO,
        TONE_SILENCE,
        PATTERN_MATCHING,
        DIPS_FINDING,
        LEAST_SQUARES,
        LSQ2
    };

    /**
     * Pauses longer than this denote a word boundary. Unit is TU.
     * Model value: sqrt(3*7) = 4.58
     * words break <---------+---------> words stick
     */
    static final double[] WORD_SPACE_LIMIT = new double[]{IGNORE, 5.0, 5.0, 5.0, 5.5, 5.6};

    /**
     * Pauses longer than this denote a character boundary. Unit is TU.
     * Model value: sqrt(1*3) = 1.73
     * chars break <---------+---------> chars cluster
     */
    static final double[] CHAR_SPACE_LIMIT = new double[]{IGNORE, 1.65, 1.65, 1.73, 2.1, 2.2};  // 2.3??

    /**
     * Tones longer than this are interpreted as a dash
     * Model value: sqrt(1*3) = 1.73
     * too many dashes <---------+---------> too few dashes
     */
    static final double[] DASH_LIMIT = new double[]{IGNORE, 1.8, 1.73, 1.7, 1.7, 1.7};

    /**
     * Tones longer than this are interpreted as two dashes
     * (dash dot = 5, dash dash = 7)
     */
    static final double TWO_DASH_LIMIT = 6.0;

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

    static final double P_CEIL_FOCUS = 50.0;

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
    static final double P_DIP_SIGMA = 0.15;

    /**
     * Ideally the separation between dips is 4 halfTus for a dot
     * and 8 halfTus for a dash. Increasing the value will cause
     * dashes to be seen as dots.
     */
    static final double P_DIP_DASHMIN = 6.1;

    /**
     * If two dashes have joined because of a weak dip, the ideal
     * dip separation would be 16 halfTus. If a dash and a dip have
     * joined, the separation is 12 halfTus. To detect the two-dash
     * feature, set a limit that is well above 12.
     */
    static final double P_DIP_TWODASHMIN = 14.0;

    /**
     * Within a multi-beep character, beep lengths are compared to
     * the length of the longest beep. If the relative length is
     * above this limit, then interpret it as a dash.
     */
    static final double P_DIP_DASHQUOTIENT = 0.68;

    /**
     * When generating a table of exponential weights, exclude entries
     * lower than this limit.
     */
    static final double P_DIP_EXPTABLE_LIM = 0.01;


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
        new SingleValueOption("q", O_STIME, "0.10");
        new SingleValueOption("s", O_SIGMA, "0.18");

        new SingleValueOption("D", O_DECODER, "5");

        new SingleValueOption("U", O_AMP_MAPPING, "3");

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
        		DECODER_NAME[decoderIndex.TONE_SILENCE.ordinal()],
        		DECODER_NAME[decoderIndex.PATTERN_MATCHING.ordinal()],
        		DECODER_NAME[decoderIndex.DIPS_FINDING.ordinal()],
        		DECODER_NAME[decoderIndex.LEAST_SQUARES.ordinal()],
        		DECODER_NAME[decoderIndex.LSQ2.ordinal()],
                GerkeLib.getDefault(O_DECODER)),
        String.format("  -U MAPPING         1: none, 2: square root, 3: logarithm, defaults to: %d", GerkeLib.getIntOpt(O_AMP_MAPPING)),
        String.format("  -u THRESHOLD       Threshold adjustment, defaults to %s", GerkeLib.getDefault(O_LEVEL)),

        String.format("  -q SAMPLE_PERIOD   sample period, defaults to %s TU", GerkeLib.getDefault(O_STIME)),
        String.format("  -s SIGMA           Gaussian sigma, defaults to %s TU", GerkeLib.getDefault(O_SIGMA)),

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

    static class Parameters {

        final int frameRate;
        final int offsetFrames;

        final double tuMillis;
        final double tsLength;
        final int framesPerSlice;

        final long wordSpaceLimit;
        final long charSpaceLimit;
        final long dashLimit;

        final double plotBegin;
        final double plotEnd;

        public Parameters(int frameRate, int offsetFrames, double tuMillis, double tsLength, int framesPerSlice,
                long wordSpaceLimit, long charSpaceLimit, long dashLimit, double plotBegin, double plotEnd) {
            this.frameRate = frameRate;
            this.offsetFrames = offsetFrames;
            this.tuMillis = tuMillis;
            this.tsLength = tsLength;
            this.framesPerSlice = framesPerSlice;
            this.wordSpaceLimit = wordSpaceLimit;
            this.charSpaceLimit = charSpaceLimit;
            this.dashLimit = dashLimit;
            this.plotBegin = plotBegin;
            this.plotEnd = plotEnd;
        }
    }


    static class Node {
        final String code;
        final String text;
        Node dash = null;
        Node dot = null;
        final int nTus;

        Node(String text, String code) {
            this.code = code;
            Node x = tree;
            int tuCount = 0;
            for (int j = 0; j < code.length() - 1; j++) {

                if (code.charAt(j) == '.') {
                    x = x.dot;
                    tuCount += 2;
                }
                else if (code.charAt(j) == '-') {
                    tuCount += 4;
                    x = x.dash;
                }
            }
            if (code.length() > 0) {
                if (code.charAt(code.length() - 1) == '.') {
                    if (x.dot != null) {
                        throw new RuntimeException(
                                String.format("duplicate node: %s", code));
                    }
                    x.dot = this;
                    tuCount += 1;
                }
                else if (code.charAt(code.length() - 1) == '-') {
                    if (x.dash != null) {
                        throw new RuntimeException(
                                String.format("duplicate node: %s", code));
                    }
                    x.dash = this;
                    tuCount += 3;
                }
            }
            this.text = text == null ? "["+code+"]" : text;
            this.nTus = tuCount;
        }

        synchronized Node newNode(String code) {
            if (code.equals("-")) {
                if (this.dash == null) {
                    final String newCode = this.code + code;
                    this.dash = new Node("[" + newCode + "]", newCode);
                    return this.dash;
                }
                else {
                    return this.dash;
                }
            }
            else if (code.equals(".")) {
                if (this.dot == null) {
                    final String newCode = this.code + code;
                    this.dot = new Node("[" + newCode + "]", newCode);
                    return this.dot;
                }
                else {
                    return this.dot;
                }
            }
            else {
                throw new IllegalArgumentException();
            }
        }
    }

    static final Node tree = new Node("", "");


    static {
        new Node("e", ".");
        new Node("t", "-");

        new Node("i", "..");
        new Node("a", ".-");
        new Node("n", "-.");
        new Node("m", "--");

        new Node("s", "...");
        new Node("u", "..-");
        new Node("r", ".-.");
        new Node("w", ".--");
        new Node("d", "-..");
        new Node("k", "-.-");
        new Node("g", "--.");
        new Node("o", "---");

        new Node("h", "....");
        new Node("v", "...-");
        new Node("f", "..-.");
        new Node(encodeLetter(252), "..--");
        new Node("l", ".-..");
        new Node(encodeLetter(228), ".-.-");
        new Node("p", ".--.");
        new Node("j", ".---");
        new Node("b", "-...");
        new Node("x", "-..-");
        new Node("c", "-.-.");
        new Node("y", "-.--");
        new Node("z", "--..");
        new Node("q", "--.-");
        new Node(encodeLetter(246), "---.");
        new Node("ch", "----");

        new Node(encodeLetter(233), "..-..");
        new Node(encodeLetter(229), ".--.-");

        new Node("0", "-----");
        new Node("1", ".----");
        new Node("2", "..---");
        new Node("3", "...--");
        new Node("4", "....-");
        new Node("5", ".....");
        new Node("6", "-....");
        new Node("7", "--...");
        new Node("8", "---..");
        new Node("9", "----.");

        new Node("/", "-..-.");

        new Node("+", ".-.-.");

        new Node(".", ".-.-.-");

        new Node(null, "--..-");
        new Node(",", "--..--");

        new Node("=", "-...-");

        new Node("-", "-....-");

        new Node(":", "---...");
        new Node(null, "-.-.-");
        new Node(";", "-.-.-.");

        new Node("(", "-.--.");
        new Node(")", "-.--.-");

        new Node("'", ".----.");

        new Node(null, "..--.");
        new Node("?", "..--..");

        new Node(null, ".-..-");
        new Node("\"", ".-..-.");

        new Node("<AS>", ".-...");

        new Node(null, "-...-.");
        new Node("<BK>", "-...-.-");

        new Node(null, "...-.");
        new Node("<SK>", "...-.-");
        new Node(null, "...-..");
        new Node("$", "...-..-");

        new Node(null, "-.-..");
        new Node(null, "-.-..-");
        new Node(null, "-.-..-.");
        new Node("<CL>", "-.-..-..");

        new Node(null, "...---");
        new Node(null, "...---.");
        new Node(null, "...---..");
        new Node("<SOS>", "...---...");
    }


    static Map<Integer, CharTemplates> templs = new TreeMap<Integer, CharTemplates>();

    static {
        new CharTemplate("a", ".-");
        new CharTemplate("b", "-...");
        new CharTemplate("c", "-.-.");
        new CharTemplate("d", "-..");
        new CharTemplate("e", ".");
        new CharTemplate("f", "..-.");
        new CharTemplate("g", "--.");
        new CharTemplate("h", "....");
        new CharTemplate("i", "..");
        new CharTemplate("j", ".---");
        new CharTemplate("k", "-.-");
        new CharTemplate("l", ".-..");
        new CharTemplate("m", "--");
        new CharTemplate("n", "-.");
        new CharTemplate("o", "---");
        new CharTemplate("p", ".--.");
        new CharTemplate("q", "--.-");
        new CharTemplate("r", ".-.");
        new CharTemplate("s", "...");
        new CharTemplate("t", "-");
        new CharTemplate("u", "..-");
        new CharTemplate("v", "...-");
        new CharTemplate("w", ".--");
        new CharTemplate("x", "-..-");
        new CharTemplate("y", "-.--");
        new CharTemplate("z", "--..");

        new CharTemplate("1", ".----");
        new CharTemplate("2", "..---");
        new CharTemplate("3", "...--");
        new CharTemplate("4", "....-");
        new CharTemplate("5", ".....");
        new CharTemplate("6", "-....");
        new CharTemplate("7", "--...");
        new CharTemplate("8", "---..");
        new CharTemplate("9", "----.");
        new CharTemplate("0", "-----");

        new CharTemplate(encodeLetter(252), "..--");
        new CharTemplate(encodeLetter(228), ".-.-");
        new CharTemplate(encodeLetter(246), "---.");
        new CharTemplate("ch", "----");
        new CharTemplate(encodeLetter(233), "..-..");

        new CharTemplate(encodeLetter(229), ".--.-");

        new CharTemplate("/", "-..-.");
        new CharTemplate("+", ".-.-.");
        new CharTemplate(".", ".-.-.-");
        new CharTemplate(null, "--..-");
        new CharTemplate(",", "--..--");
        new CharTemplate("=", "-...-");
        new CharTemplate("-", "-....-");
        new CharTemplate(null, ".-....-");
        new CharTemplate(":", "---...");
        new CharTemplate(null, "-.-.-");
        new CharTemplate(";", "-.-.-.");
        new CharTemplate("(", "-.--.");
        new CharTemplate(")", "-.--.-");
        new CharTemplate("'", ".----.");
        new CharTemplate(null, "..--.");
        new CharTemplate("?", "..--..");
        new CharTemplate(null, ".-..-");
        new CharTemplate("\"", ".-..-.");
        new CharTemplate("<AS>", ".-...");
        new CharTemplate(null, "-...-.");
        new CharTemplate("<BK>", "-...-.-");
        new CharTemplate(null, "...-.");
        new CharTemplate("<SK>", "...-.-");
        new CharTemplate(null, "...-..");
        new CharTemplate("$", "...-..-");
        new CharTemplate(null, "-.-..");
        new CharTemplate(null, "-.-..-");
        new CharTemplate(null, "-.-..-.");
        new CharTemplate("<CL>", "-.-..-..");
        new CharTemplate(null, "...---");
        new CharTemplate(null, "...---.");
        new CharTemplate(null, "...---..");
        new CharTemplate("<SOS>", "...---...");
    }


    private static String encodeLetter(int i) {
        return new String(new int[]{i}, 0, 1);
    }


    static class Formatter {
        StringBuilder sb = new StringBuilder();
        int pos = 0;
        final MessageDigest md;
        final byte[] sp = new byte[]{' '};

        Formatter() throws NoSuchAlgorithmException {
            md = MessageDigest.getInstance("MD5");
        }

        private static final int lineLength = 72;

        /**
         * @param wordBreak
         * @param text
         * @param timestamp        -1 for no timestamp
         */
        void add(boolean wordBreak, String text, int timestamp) {

            //new Info("word break: %b, text: '%s'", wordBreak, text);

            sb.append(text);

            if (wordBreak) {
                md.update(sp);
                md.update(sb.toString().getBytes(Charset.forName("UTF-8")));
            }

            if (timestamp != -1) {
                sb.append(String.format(" /%d/", timestamp));
            }

            if (wordBreak) {
                if (pos + 1 + sb.length() > lineLength) {
                    newLine();
                    System.out.print(sb.toString());
                    pos = sb.length();
                }
                else if (pos > 0) {
                    System.out.print(" ");
                    System.out.print(sb.toString());
                    pos += 1 + sb.length();
                }
                else {
                    System.out.print(sb.toString());
                    pos = sb.length();
                }
                sb = new StringBuilder();
            }
        }

        int getPos() {
            return pos;
        }

        void newLine() {
            System.out.println();
            pos = 0;
        }

        String getDigest() {
            final byte[] by = md.digest();
            final StringBuilder b = new StringBuilder();
            for (int i = 0; i < by.length; i++) {
                b.append(String.format("%02x", by[i] < 0 ? by[i]+256 : by[i]));
            }
            return b.toString();
        }

        void flush() {
            if (sb.length() > 0) {
                add(true, "", -1);
            }
        }
    }





    static class Trans {
        final int q;
        final boolean rise;
        final double dipAcc;
        final double spikeAcc;
        final double ceiling;
        final double floor;

        Trans(int q, boolean rise, double ceiling, double floor) {
            this(q, rise, -1.0, ceiling, floor);
        }

        Trans(int q, boolean rise, double sigAcc, double ceiling, double floor) {
            this.q = q;
            this.rise = rise;
            this.ceiling = ceiling;
            this.floor = floor;
            if (rise) {
                this.dipAcc = sigAcc;
                this.spikeAcc = -1.0;
            }
            else {
                this.spikeAcc = sigAcc;
                this.dipAcc = -1.0;
            }
        }
    }

    static class CharTemplates {
        final List<CharTemplate> list;

        public CharTemplates(List<CharTemplate> list) {
            this.list = list;
        }
    }


    static class CharTemplate {
        final int[] pattern;
        final String text;

        public CharTemplate(String text, String code) {

            this.text = text == null ? "["+code+"]" : text;

            int size = 0;
            int count = 0;
            for (char x : code.toCharArray()) {
                count++;
                if (x == '.') {
                    size++;
                }
                else if (x == '-') {
                    size += 3;
                }
            }
            size += (count-1);
            final Integer sizeKey = Integer.valueOf(size);

            pattern = new int[size];
            for (int k = 0; k < pattern.length; k++) {
                pattern[k] = LO;
            }

            int index = 0;
            for (char x : code.toCharArray()) {
                if (x == '.') {
                    pattern[index] = HI;
                    index += 2;
                }
                else if (x == '-') {
                    pattern[index] = HI;
                    pattern[index+1] = HI;
                    pattern[index+2] = HI;
                    index += 4;
                }
            }

            for (int i = 0; i < pattern.length; i++) {
                if (pattern[i] != LO && pattern[i] != HI) {
                    new Death("bad CharTemplate");  // impossible
                }
            }

            final CharTemplates existing = templs.get(sizeKey);
            if (existing == null) {
                final List<CharTemplate> list = new ArrayList<CharTemplate>();
                list.add(this);
                templs.put(sizeKey, new CharTemplates(list));
            }
            else {
                existing.list.add(this);
            }
        }
    }

    static class CharData {
        List<Trans> transes;
        Trans lastAdded = null;

        public CharData() {
            this.transes = new ArrayList<Trans>();
        }

        public CharData(Trans trans) {
            this.transes = new ArrayList<Trans>();
            add(trans);
        }

        void add(Trans trans) {
            transes.add(trans);
            lastAdded = trans;
        }

        boolean isComplete() {
            return lastAdded.rise == false;
        }

        boolean isEmpty() {
            return transes.isEmpty();
        }
    }

    static abstract class Dip implements Comparable<Dip> {

        final int q;
        final double strength;

        public Dip(int q, double strength) {
            this.q = q;
            this.strength = strength;
        }
    }

    static class DipByStrength extends Dip {
        public DipByStrength(int q, double strength) {
            super(q, strength);
        }

        @Override
        public int compareTo(Dip o) {
            // strongest elements at beginning of set
            return this.strength < o.strength ? 1 : this.strength == o.strength ? 0 : -1;
        }
    }

    static class DipByTime extends Dip {
        public DipByTime(int q, double strength) {
            super(q, strength);
        }

        @Override
        public int compareTo(Dip o) {
            // chronological order
            return this.q < o.q ? -1 : this.q == o.q ? 0 : 1;
        }
    }

    /**
     * Represents a dash or a dot.
     */
    static class Beep {
        final int extent;

        public Beep(int extent) {
            this.extent = extent;
        }
    }

    static class TimeCounter {
        int chCus = 0;
        int chTicks = 0;
    }

    static abstract class WeightBase {
        // returns weight for j in [-jMax, ..., 0, ..., jMax]
        final int jMax;
        public WeightBase(int jMax) {
            this.jMax = jMax;
        }
        abstract double w(int j);
    }

    static class WeightDash extends WeightBase {

        public WeightDash(int jMax) {
            super(jMax);
        }

        double w(int j) {
            return 1.0;
        }
    }

    static class WeightTwoDots extends WeightBase {

        public WeightTwoDots(int jMax) {
            super(jMax);
        }

        double w(int j) {
            final int j3 = jMax/3;
            return j > j3 || j < -j3 ? 1.0 : 0.0;
        }

    }

    static class WeightDot extends WeightBase {
        public WeightDot(int jMax) {
            super(jMax);
        }

        double w(int j) {
            return 1.0;
        }
    }

    static class TwoDoubles {
        final double a;
        final double b;
        public TwoDoubles(double a, double b) {
            this.a = a;
            this.b = b;
        }
    }

    static class Cluster {
        final Integer lowestKey;
        final List<Integer> members = new ArrayList<Integer>();

        public Cluster(Integer a) {
            lowestKey = a;
            members.add(a);
        }

        void add(Integer b) {
            members.add(b);
        }
    }

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


            // ============  Multiply by sine and cosine functions, apply filtering

            final int nofSlices = w.nofFrames/framesPerSlice;

            final int fSpecified = GerkeLib.getIntOpt(GerkeDecoder.O_FREQ);
            
            final CwDetector detector = new CwBasicImpl(
            		nofSlices,
            		w,
            		tuMillis,
            		framesPerSlice,
            		tsLength,
            		fSpecified
            		);

            final Signal signal = detector.getSignal();
            final double[] sig = signal.sig;
            final int sigSize = signal.sig.length;
            
            final int fBest = signal.fBest;
            final int clipLevel = signal.clipLevel;
            

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

                flo[q] = localFloorByHist(q, sig, estBaseFloor);
                cei[q] = localCeilByHist(q, sig, estBaseCeil);
                ceilingMax = Compute.dMax(ceilingMax, cei[q]);
            }

            // some of this is used by the phase plot

            final int decoder = GerkeLib.getIntOpt(O_DECODER);

            final int dashLimit = (int) Math.round(DASH_LIMIT[decoder]*tuMillis*w.frameRate/(1000*framesPerSlice));        // PARAMETER
            final int wordSpaceLimit = (int) Math.round(WORD_SPACE_LIMIT[decoder]*tuMillis*w.frameRate/(1000*framesPerSlice));   // PARAMETER
            final int charSpaceLimit = (int) Math.round(CHAR_SPACE_LIMIT[decoder]*tuMillis*w.frameRate/(1000*framesPerSlice));   // PARAMETER
            final int twoDashLimit = (int) Math.round(TWO_DASH_LIMIT*tuMillis*w.frameRate/(1000*framesPerSlice));     // PARAMETER
            final double level = GerkeLib.getDoubleOpt(O_LEVEL);
            final double levelLog = Math.log(level);

            new Info("relative tone/silence threshold: %.3f", level);
            new Debug("dash limit: %d, word space limit: %d", dashLimit, wordSpaceLimit);

            // ================ Phase plot, optional
            
            // TODO .. not supported by all detectors

            if (GerkeLib.getFlag(O_PPLOT)) {
                phasePlot(fBest, nofSlices, framesPerSlice, w, clipLevel,
                        sig, level, levelLog, flo, cei, decoder, ampMap);
            }

            // ============== identify transitions
            // usage depends on decoder method

            final Trans[] trans = new Trans[nofSlices];
            int transIndex = 0;
            boolean tone = false;
            double dipAcc = 0.0;
            double spikeAcc = 0.0;
            double thresholdMax = -1.0;
            // double ceilingMax = -1.0; .. already done
            new Debug("thresholdMax is: %e", thresholdMax);



            for (int q = 0; true; q++) {
                if (w.wav.length - q*framesPerSlice < framesPerSlice) {
                    break;
                }

                final double threshold = threshold(decoder, ampMap, level, levelLog, flo[q], cei[q]);
                thresholdMax = Compute.dMax(threshold, thresholdMax);

                final boolean newTone = sig[q] > threshold;

                if (newTone && !tone) {
                    // raise
                    if (transIndex > 0 && q - trans[transIndex-1].q <= charSpaceLimit) {
                        trans[transIndex] = new Trans(q, true, dipAcc, cei[q], flo[q]);
                    }
                    else {
                        trans[transIndex] = new Trans(q, true, cei[q], flo[q]);
                    }
                    transIndex++;
                    tone = true;
                    spikeAcc = squared(threshold - sig[q]);
                }
                else if (!newTone && tone) {
                    // fall
                    trans[transIndex] = new Trans(q, false, spikeAcc, cei[q], flo[q]);
                    transIndex++;
                    tone = false;
                    dipAcc = squared(threshold - sig[q]);
                }
                else if (!tone) {
                    dipAcc += squared(threshold - sig[q]);
                }
                else if (tone) {
                    spikeAcc += squared(threshold - sig[q]);
                }
            }

            new Debug("transIndex: %d", transIndex);

            // Eliminate small dips

            final double silentTu = (1.0/tsLength)*(0.5*thresholdMax)*(0.5*thresholdMax);
            new Debug("thresholdMax is: %e", thresholdMax);
            new Debug("tsLength is: %e", tsLength);
            new Debug("silentTu is: %e", silentTu);
            final double dipLimit = GerkeLib.getDoubleOptMulti(O_HIDDEN)[HiddenOpts.DIP.ordinal()];
            final int veryShortDip = (int) Math.round(0.2/tsLength);  // PARAMETER 0.2

            for (int t = 1; t < transIndex; t++) {
                if (transIndex > 0 &&
                        trans[t].rise &&
                        trans[t].dipAcc != -1.0 &&
                        (trans[t].dipAcc < dipLimit*silentTu || trans[t].q - trans[t-1].q <= veryShortDip)) {
                    new Trace("dip at: %d, width: %d, mass: %f, fraction: %f",
                            t,
                            trans[t].q - trans[t-1].q,
                            trans[t].dipAcc,
                            trans[t].dipAcc/silentTu);
                    if (t+1 < transIndex) {
                        // preserve accumulated spike value
                        trans[t+1] =
                                new Trans(trans[t+1].q,
                                        false,
                                        trans[t+1].spikeAcc + trans[t-1].spikeAcc,
                                        trans[t].ceiling,
                                        trans[t].floor
                                        );
                    }
                    trans[t-1] = null;
                    trans[t] = null;
                }
                else if (transIndex > 0 &&
                        trans[t].rise &&
                        trans[t].dipAcc != -1.0 && t % 200 == 0) {
                    new Trace("dip at: %d, width: %d, mass: %e, limit: %e",
                            t,
                            trans[t].q - trans[t-1].q,
                            trans[t].dipAcc,
                            dipLimit*silentTu);
                }
            }

            transIndex = removeHoles(trans, transIndex);

            // check if potential spikes exist
            boolean hasSpikes = false;
            for (int t = 0; t < transIndex; t++) {
                if (trans[t].spikeAcc > 0) {
                    hasSpikes = true;
                    break;
                }
            }

            // remove spikes

            final int veryShortSpike = (int) Math.round(0.2/tsLength);  // PARAMETER 0.2
            if (hasSpikes) {
                final double spikeLimit = GerkeLib.getDoubleOptMulti(O_HIDDEN)[HiddenOpts.SPIKE.ordinal()];
                for (int t = 1; t < transIndex; t++) {
                    if (!trans[t].rise && trans[t].spikeAcc != -1.0 &&
                            (trans[t].spikeAcc < spikeLimit*silentTu || trans[t].q - trans[t-1].q <= veryShortSpike)) {
                        trans[t-1] = null;
                        trans[t] = null;
                    }
                }
            }
            transIndex = removeHoles(trans, transIndex);

            // break up very long dashes

            final boolean breakLongDash =
                    GerkeLib.getIntOptMulti(O_HIDDEN)
                    [HiddenOpts.BREAK_LONG_DASH.ordinal()] == 1;

            if (breakLongDash) {
                // TODO, refactor .. break off the first dash, then reconsider the rest
                for (int t = 1; t < transIndex; ) {
                    if (trans[t-1].rise && !trans[t].rise && trans[t].q - trans[t-1].q > twoDashLimit) {
                        // break up a very long dash, make room for 2 more events
                        for (int tt = transIndex-1; tt > t; tt--) {
                            trans[tt+2] = trans[tt];
                        }
                        transIndex += 2;
                        // fair split
                        Trans big = trans[t];
                        int dashSize = big.q - trans[t-1].q;
                        int q1 = trans[t-1].q + dashSize/2 - (int) Math.round(0.5/tsLength);
                        int q2 = trans[t-1].q + dashSize/2 + (int) Math.round(0.5/tsLength);
                        int q3 = big.q;
                        double acc = big.spikeAcc;
                        double ceiling = trans[t-1].ceiling;
                        double floor = trans[t-1].floor;
                        trans[t] = new Trans(q1, false, acc/2, ceiling, floor);
                        trans[t+1] = new Trans(q2, true, acc/2, ceiling, floor);
                        trans[t+2] = new Trans(q3, false, acc/2, ceiling, floor);
                        t +=3;
                    }
                    else {
                        t++;
                    }
                }
            }

            ///////////////////////
            // transition list is ready
            ///////////////////////

            if (transIndex == 0) {
                new Death("no signal detected");
            }
            else if (transIndex == 1) {
                new Death("no code detected");
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
            if (decoder == decoderIndex.TONE_SILENCE.ordinal()) {
                decodeByLevels(
                        formatter,
                        plotEntries,
                        trans,
                        transIndex,
                        tuMillis,
                        tsLength,
                        wordSpaceLimit,
                        charSpaceLimit,
                        dashLimit,
                        plotLimits,
                        framesPerSlice,
                        w.frameRate,
                        w.offsetFrames,
                        ceilingMax,
                        offset);
            }
            else if (decoder == decoderIndex.PATTERN_MATCHING.ordinal()) {
                decodeByPatterns(
                        decoder,
                        ampMap,
                        formatter,
                        plotEntries,
                        trans,
                        transIndex,
                        sig,
                        ceilingMax,
                        tsLength, tuMillis,
                        level,
                        levelLog,
                        wordSpaceLimit,
                        charSpaceLimit,
                        framesPerSlice, w.frameRate, w.offsetFrames, plotLimits, offset);
            }
            else if (decoder == decoderIndex.DIPS_FINDING.ordinal()) {
                decodeByGaps(
                        formatter,
                        plotEntries,
                        trans,
                        transIndex,
                        sig,
                        cei,
                        flo,
                        wordSpaceLimit,
                        charSpaceLimit,
                        offset,
                        tsLength,
                        tuMillis,
                        plotLimits,
                        ceilingMax,
                        framesPerSlice, w.frameRate, w.offsetFrames);
            }
            else if (decoder == decoderIndex.LEAST_SQUARES.ordinal()) {
                    decodeByLeastSquares(
                            formatter,
                            plotEntries,
                            sig,
                            cei,
                            flo,
                            sigSize,
                            
                            offset,
                            tsLength,
                            tuMillis,
                            
                            plotLimits,
                            framesPerSlice,
                            w.frameRate,
                            w.offsetFrames,
                            ceilingMax
                            );
            }
            else if (decoder == decoderIndex.LSQ2.ordinal()) {
                decodeLsq2(
                        formatter,
                        plotEntries,
                        sig,
                        cei,
                        flo,
                        sigSize,
                        
                        offset,
                        tsLength,
                        tuMillis,
                        
                        plotLimits,
                        framesPerSlice,
                        w.frameRate,
                        w.offsetFrames,
                        ceilingMax,
                        
                        ampMap,
                        level, levelLog
                        );
        }
            else {
                new Death("no such decoder: '%d'", decoder);
            }

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

    private static void decodeByLevels(
            Formatter formatter,
            PlotEntries plotEntries,
            Trans[] trans, int transIndex,
            double tuMillis,
            double tsLength,
            int wordSpaceLimit,
            int charSpaceLimit,
            int dashLimit,
            double[] plotLimits,
            int framesPerSlice,
            int frameRate,
            int offsetFrames,
            double ceilingMax,
            int offset) throws IOException, NoSuchAlgorithmException, InterruptedException
    {


        if (plotEntries != null) {

            boolean firstLap = true;
            for (int t = 0; t < transIndex; t++) {

                final double sec = timeSeconds(trans[t].q, framesPerSlice, frameRate, offsetFrames);

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

        Node p = tree;

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
                    p = tree;
                    qCharBegin = trans[t].q;
                }
                else if (trans[t].q - trans[t-1].q > wordSpaceLimit) {
                    if (GerkeLib.getFlag(O_TSTAMPS)) {
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

                    p = tree;
                }
                else if (trans[t].q - trans[t-1].q > charSpaceLimit) {
                    formatter.add(false, p.text, -1);

                    spTicksC += trans[t].q - trans[t-1].q;
                    spCusC += 3;
                    chTicks += trans[t-1].q - qCharBegin;
                    chCus += p.nTus;
                    qCharBegin = trans[t].q;

                    p = tree;
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

        if (p != tree) {
            formatter.add(true, p.text, -1);
            formatter.newLine();

            chTicks += trans[transIndex-1].q - qCharBegin;
            chCus += p.nTus;
        }
        else if (p == tree && formatter.getPos() > 0) {
            formatter.flush();
            formatter.newLine();
        }

        wpmReport(chCus, chTicks, spCusW, spTicksW, spCusC, spTicksC, tuMillis, tsLength);
    }





    /**
     * Decode and optionally plot
     *
     * @param trans
     * @param transIndex
     * @param sig
     * @param tsLength
     * @param tuMillis
     * @param wordSpaceLimit
     * @param charSpaceLimit
     * @param dashLimit
     * @throws Exception
     */
    private static void decodeByPatterns(
            int decoder,
            int ampMap,
            Formatter formatter,
            PlotEntries plotEntries,
            Trans[] trans, int transIndex, double[] sig,
            double ceilingMax,
            double tsLength,
            double tuMillis,
            double level,
            double levelLog,
            int wordSpaceLimit,
            int charSpaceLimit,
            int framesPerSlice,
            int frameRate,
            int offsetFrames,
            double[] plotLimits,
            int offset) throws Exception {

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
                ts = GerkeLib.getFlag(O_TSTAMPS) ?
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
                            final double seconds = offset + timeSeconds(cd.transes.get(0).q, framesPerSlice, frameRate, offsetFrames);
                            if (plotLimits[0] <= seconds && seconds <= plotLimits[1]) {
                                plotDecoded(plotEntries, cd, ct, offset, (tsLength*tuMillis)/1000, ceilingMax,
                                        framesPerSlice, frameRate, offsetFrames);
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

        final int qSize = cd.lastAdded.q - cd.transes.get(0).q;
        final int tuClass = (int) Math.round(tsLength*qSize);

        // PARAMETER 1.00, skew the char class a little, heuristic
        final double tuClassD = 1.00*tsLength*qSize;

        final Set<Entry<Integer, CharTemplates>> candsAll = templs.entrySet();

        final double zigma = 0.7; // PARAMETER
        CharTemplate best = null;
        double bestSum = -999999.0;
        for (Entry<Integer, CharTemplates> eict : candsAll) {

            final int j = eict.getKey().intValue();

            final double weight = Math.exp(-squared((tuClassD - j)/zigma)/2);


            for (CharTemplate cand : eict.getValue().list) {

                int candSize = cand.pattern.length;
                double sum = 0.0;

                double deltaCeiling = cd.lastAdded.ceiling - cd.transes.get(0).ceiling;
                double deltaFloor = cd.lastAdded.floor - cd.transes.get(0).floor;

                double slopeCeiling = deltaCeiling/qSize;
                double slopeFloor = deltaFloor/qSize;
                for (int q = cd.transes.get(0).q; q < cd.lastAdded.q; q++) {

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

        if (GerkeLib.getIntOpt(O_VERBOSE) >= 3) {
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

        int prevValue = LO;

        final int nTranses = cd.transes.size();

        // time of 1 char
        final double tChar = (cd.transes.get(nTranses-1).q - cd.transes.get(0).q)*tsSecs;

        for (int i = 0; i < ct.pattern.length; i++) {
            if (ct.pattern[i] == HI && prevValue == LO) {
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
            else if (ct.pattern[i] == LO && prevValue == HI) {
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

    private static void decodeByGaps(
            Formatter formatter,
            PlotEntries plotEntries,
            Trans[] trans,
            int transIndex,
            double[] sig,
            double[] cei,
            double[] flo,
            int wordSpaceLimit,
            int charSpaceLimit,
            int offset,
            double tsLength,
            double tuMillis,
            double[] plotLimits,
            double ceilingMax,
            int framesPerSlice, int frameRate, int offsetFrames) throws IOException, InterruptedException, NoSuchAlgorithmException {

        /**
-        * Merge dips when closer than this distance. Unit is TUs.
-        */
        final double dipMergeLim = GerkeLib.getDoubleOptMulti(O_HIDDEN)[HiddenOpts.DIP_MERGE_LIM.ordinal()];

        /**
         * Dips of lesser strength are ignored. A too high value will cause
         * weak dips to be ignored, so that 'i' prints as 't' for example.
         */
        final double dipStrengthMin = GerkeLib.getDoubleOptMulti(O_HIDDEN)[HiddenOpts.DIP_STRENGTH_MIN.ordinal()];

        if (plotEntries != null) {
            // make one "decode" entry at left edge of plot
            plotEntries.addDecoded(plotLimits[0], PlotEntryDecode.height*ceilingMax);
        }

        int charNo = 0;

        // find characters
        // this is similar to what the level decoder does
        boolean prevTone = false;
        int beginChar = -1;

        int spTicksC = 0;    // inter-char spaces: time-slice ticks
        int spCusC = 0;      // inter-char spaces: code units

        int spTicksW = 0;    // inter-word spaces: time-slice ticks
        int spCusW = 0;      // inter-word spaces: code units

        final TimeCounter tc = new TimeCounter();

        for (int t = 0; t < transIndex; t++) {
            final boolean newTone = trans[t].rise;
            final double spExp = GerkeLib.getDoubleOpt(O_SPACE_EXP);
            if (t == 0 && !(trans[t].rise)) {
                new Death("assertion failure, first transition is not a rise");
            }

            if (!prevTone && newTone) {
                // silent -> tone
                if (t == 0) {
                    beginChar = trans[t].q;
                }
                else if (trans[t].q - trans[t-1].q > spExp*wordSpaceLimit) {
                    decodeGapChar(beginChar, trans[t-1].q, sig, cei, flo, tsLength,
                            dipMergeLim, dipStrengthMin,
                            charNo++,
                            formatter, plotEntries, ceilingMax, framesPerSlice, frameRate, offsetFrames,
                            plotLimits,
                            tc);
                    final int ts =
                            GerkeLib.getFlag(O_TSTAMPS) ?
                            offset + (int) Math.round(trans[t].q*tsLength*tuMillis/1000) : -1;
                    formatter.add(true, "", ts);
                    beginChar = trans[t].q;
                    spCusW += 7;
                    spTicksW += trans[t].q - trans[t-1].q;
                }
                else if (trans[t].q - trans[t-1].q > spExp*charSpaceLimit) {
                    decodeGapChar(beginChar, trans[t-1].q, sig, cei, flo, tsLength,
                            dipMergeLim, dipStrengthMin,
                            charNo++,
                            formatter, plotEntries, ceilingMax, framesPerSlice, frameRate, offsetFrames,
                            plotLimits,
                            tc);
                    beginChar = trans[t].q;
                    spCusC += 3;
                    spTicksC += trans[t].q - trans[t-1].q;
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
                    formatter, plotEntries, ceilingMax, framesPerSlice, frameRate, offsetFrames,
                    plotLimits,
                    tc);
        }

        formatter.flush();
        formatter.newLine();

        wpmReport(tc.chCus, tc.chTicks, spCusW, spTicksW, spCusC, spTicksC, tuMillis, tsLength);
    }

    private static void decodeGapChar(
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
            double ceilingMax,
            int framesPerSlice, int frameRate, int offsetFrames,
            double[] plotLimits,
            TimeCounter tc) throws IOException, InterruptedException {

        tc.chTicks += q2 - q1;

        final boolean inView = plotEntries != null &&
                timeSeconds(q1, framesPerSlice,frameRate, offsetFrames) >= plotLimits[0] &&
                timeSeconds(q2, framesPerSlice,frameRate, offsetFrames) <= plotLimits[1];

        final double decodeLo = ceilingMax*PlotEntryDecode.height;
        final double decodeHi = ceilingMax*2*PlotEntryDecode.height;

        if (inView) {
            plotEntries.addDecoded(timeSeconds(q1, framesPerSlice, frameRate, offsetFrames), decodeLo);
            plotEntries.addDecoded(timeSeconds(q1+1, framesPerSlice, frameRate, offsetFrames), decodeHi);
            plotEntries.addDecoded(timeSeconds(q2-1, framesPerSlice, frameRate, offsetFrames), decodeHi);
            plotEntries.addDecoded(timeSeconds(q2, framesPerSlice, frameRate, offsetFrames), decodeLo);
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
        final double z = (cei[k1] - flo[k1] + cei[k2] - flo[k2])*(1/(2*tau))*P_DIP_SIGMA;

        for (int k = k1; k < k2; k++) {
            // compute a weighted sum
            double acc = (cei[k] - sig[k]);
            for (int d = 1; true; d++) {
                double w = Math.exp(-squared(d*tau/P_DIP_SIGMA)/2);
                if (w < P_DIP_EXPTABLE_LIM || k-d < 0 || k+d >= sig.length) {
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
                plotEntries.addDecoded(timeSeconds(d.q - halfTu, framesPerSlice, frameRate, offsetFrames), decodeHi);
                plotEntries.addDecoded(timeSeconds(d.q - halfTu + 1, framesPerSlice, frameRate, offsetFrames), decodeLo);
                plotEntries.addDecoded(timeSeconds(d.q + halfTu - 1, framesPerSlice, frameRate, offsetFrames), decodeLo);
                plotEntries.addDecoded(timeSeconds(d.q + halfTu, framesPerSlice, frameRate, offsetFrames), decodeHi);
            }
        }

        // interpretation follows!
        Node p = tree;
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

                if (extent > P_DIP_TWODASHMIN*halfTu) {
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
            if (beeps.get(0).extent > P_DIP_DASHMIN*halfTu) {
                p = p.newNode("-");
            }
            else {
                p = p.newNode(".");
            }
        }
        else if (extentMax <= P_DIP_DASHMIN*halfTu) {
            // only dots, two or more of them
            for (int m = 0; m < beeps.size(); m++) {
                p = p.newNode(".");
            }
        }
        else {
            // at least one dash
            for (Beep beep : beeps) {
                if (beep.extent/extentMaxD > P_DIP_DASHQUOTIENT) {
                    p = p.newNode("-");
                }
                else {
                    p = p.newNode(".");
                }
            }
        }

        new Debug("char no: %d, decoded: %s", charNo, p.text);
        formatter.add(false, p.text, -1);
        tc.chCus += p.nTus;
    }


    public static class ToneBase {
        final int k;
        final int rise;
        final int drop;
        public ToneBase(int k, int rise, int drop) {
            this.k = k;
            this.rise = rise;
            this.drop = drop;
        }
    }

    /**
     * Represents a dash, centered at index k. Indexes for the rise and drop
     * are specified explicitly.
     */
    public static class Dash extends ToneBase {

        final double ceiling;
        
        public Dash(int k, int rise, int drop, double ceiling) {
            super(k, rise, drop);
            this.ceiling = ceiling;
        }


        public Dash(int k, int jDot, int jDash, double sig[], double ceiling, boolean improve) {
        	super(k, dashRise(k, sig, jDot, jDash, ceiling, improve), dashDrop(k, sig, jDot, jDash, ceiling, improve));
        	this.ceiling = ceiling;
        }


        private static int dashRise(int k, double[] sig, int jDot, int jDash, double ceiling, boolean improve) {

        	if (!improve) {
        		return k - jDash;
        	} 
        	else {
        		int bestRise;
        		try {
        			final double q = 0.5;
        			final WeightBase w = new WeightDot(jDot);
        			final TwoDoubles x = lsq(sig, k - jDash, jDot, w);
        			final int jx = (int) Math.round((ceiling - q - x.a)/x.b);
        			// new Debug("jDash: %d, jx: %d, jy: %d", jDash, jx, jy);
        			final int jxAbs = jx < 0 ? -jx : jx;
        			bestRise = jxAbs > 2*jDot ? k - jDash : k - jDash + jx;
        		}
        		catch (Exception e) {
        			bestRise = k - jDash;
        		}

        		return bestRise;
        	}
        }
			

        private static int dashDrop(int k, double[] sig, int jDot, int jDash, double ceiling, boolean improve) {
        	if (!improve) {
        		return k + jDash;
        	} 
        	else {
        		int bestDrop;
        		try {
        			final double q = 0.5;
        			final WeightBase w = new WeightDot(jDot);
        			TwoDoubles y = lsq(sig, k + jDash, jDot, w);
        			final int jy = (int) Math.round((ceiling - q - y.a)/y.b);
        			// new Debug("jDash: %d, jx: %d, jy: %d", jDash, jx, jy);
        			final int jyAbs = jy < 0 ? -jy : jy;
        			bestDrop = jyAbs > 2*jDot ? k + jDash : k + jDash + jy;
        		}
        		catch (Exception e) {
        			bestDrop = k + jDash;
        		}
        		return bestDrop;
        	}
        }
    }

    /**
     * Represents a dot, centered at index k. The extent is implied.
     */
    public static class Dot extends ToneBase {
        public Dot(int k, int rise, int drop) {
            super(k, rise, drop);
        }
    }
    
    public static class HighValue {
    	final Integer k;
    	final double value;
    	
		public HighValue(Integer k, double value) {
			this.k = k;
			this.value = value;
		}
    }

    private static void decodeLsq2(
            Formatter formatter,
            PlotEntries plotEntries,
            double[] sig,
            double[] cei,
            double[] flo,
            int sigSize,
            
            int offset,
            double tsLength,
            double tuMillis,
            
            double[] plotLimits,
            int framesPerSlice,
            int frameRate,
            int offsetFrames,
            double ceilingMax,
            
    		int ampMap,
    		double level,
    		double levelLog) {

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
        			(k - highBegin)*tsLength < DASH_LIMIT[decoderIndex.LSQ2.ordinal()]) {
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

        Node p = tree;
        int qCharBegin = -999999;
        Integer prevKey = null;
        for (Integer key : dashes.navigableKeySet()) {

        	if (prevKey == null) {
        		qCharBegin = lsqToneBegin(key, dashes, jDot);
        	}
            if (prevKey != null && toneDist2(prevKey, key, dashes, jDash, jDot) >
            WORD_SPACE_LIMIT[decoder]/tsLength) {
            	
            	//formatter.add(true, p.text, -1);
            	final int ts =
                        GerkeLib.getFlag(O_TSTAMPS) ?
                        offset + (int) Math.round(key*tsLength*tuMillis/1000) : -1;
                formatter.add(true, p.text, ts);
                chCus += p.nTus;
                spCusW += 7;
                spTicksW += lsqToneBegin(key, dashes, jDot) - lsqToneEnd(prevKey, dashes, jDot);
                
                p = tree;
                final ToneBase tb = dashes.get(key);
                if (tb instanceof Dash) {
                	p = p.newNode("-");
                }
                else {
                	p = p.newNode(".");
                }
                chTicks += lsqToneEnd(prevKey, dashes, jDot) - qCharBegin;
                qCharBegin = lsqToneBegin(key, dashes, jDot);
                lsqPlotHelper(plotEntries, plotLimits, key, tb, jDot, framesPerSlice, frameRate, offsetFrames, ceilingMax);
            }
            else if (prevKey != null && toneDist2(prevKey, key, dashes, jDash, jDot) > CHAR_SPACE_LIMIT[decoder]/tsLength) {
                formatter.add(false, p.text, -1);
                chCus += p.nTus;
                spCusC += 3;
                
                spTicksC += lsqToneBegin(key, dashes, jDot) - lsqToneEnd(prevKey, dashes, jDot);

                p = tree;
                final ToneBase tb = dashes.get(key);
                if (tb instanceof Dash) {
                	p = p.newNode("-");
                }
                else {
                	p = p.newNode(".");
                }
                chTicks += lsqToneEnd(prevKey, dashes, jDot) - qCharBegin;
                qCharBegin = lsqToneBegin(key, dashes, jDot);
                lsqPlotHelper(plotEntries, plotLimits, key, tb, jDot, framesPerSlice, frameRate, offsetFrames, ceilingMax);
            }
            else {
            	final ToneBase tb = dashes.get(key);
            	p = tb instanceof Dash ? p.newNode("-") : p.newNode(".");  
            	lsqPlotHelper(plotEntries, plotLimits, key, tb, jDot, framesPerSlice, frameRate, offsetFrames, ceilingMax);
            }
            prevKey = key;
        }

        if (p != tree) {
            formatter.add(true, p.text, -1);
            formatter.newLine();
            chCus += p.nTus;
            chTicks += lsqToneEnd(prevKey, dashes, jDot) - qCharBegin;
        }

        wpmReport(chCus, chTicks, spCusW, spTicksW, spCusC, spTicksC, tuMillis, tsLength);
    }

    private static boolean testAndUpdate(double value, double thr) {
		return value > thr;
    }
    
    private static void decodeByLeastSquares(
            Formatter formatter,
            PlotEntries plotEntries,
            double[] sig,
            double[] cei,
            double[] flo,
            int sigSize,
            
            int offset,
            double tsLength,
            double tuMillis,
            
            double[] plotLimits,
            int framesPerSlice,
            int frameRate,
            int offsetFrames,
            double ceilingMax) {
    	
    	final int decoder = decoderIndex.LEAST_SQUARES.ordinal();
    	
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

        final int jDashSmall = (int) Math.round(1.35/tsLength);

        final double dashStrengthLimit = 0.6;
        
        final double dotStrengthLimit = 0.8;
        //final double dotStrengthLimit = 0.55;
        
        final double dotPeakCrit = 0.3;
        //final double dotPeakCrit = 0.35;

        final double mergeDashesWhenCloser = 2.8/tsLength;
        final double mergeDotsWhenCloser = 0.8/tsLength;

        final WeightBase w = new WeightDash(jDash);
        final WeightBase wDot = new WeightDot(jDot);  // arg ignored really
        //final WeightBase w2 = new WeightTwoDots(jDash);
        //double prevB = Double.MAX_VALUE;

        TwoDoubles prevD = new TwoDoubles(0.0, Double.MAX_VALUE);

        for (int k = 0 + jDash; k < sigSize - jDash; k++) {
            final TwoDoubles r = lsq(sig, k, jDash, w);

            if (prevD.b >= 0.0 && r.b < 0.0) {

                final int kBest = prevD.b > -r.b ? k : k-1;

                final TwoDoubles aa = lsq(sig, kBest-2*jDot, jDot, wDot);
                final TwoDoubles bb = lsq(sig, kBest, jDot, wDot);
                final TwoDoubles cc = lsq(sig, kBest+2*jDot, jDot, wDot);

                if ((kBest == k ?
                        cei[kBest] - r.a < dashStrengthLimit :
                            cei[kBest] - prevD.a < dashStrengthLimit) &&

                        // PARA - this setting means that a dip in the middle
                        // of the dash will be somewhat protected against.
                        cei[kBest-2*jDot] - aa.a < 0.5 &&
                        cei[kBest] - bb.a < 0.43 &&
                        cei[kBest+2*jDot] - cc.a < 0.5

                        ) {  // logarithmic assumed

                	try {
                		// index out of bounds can happen, hence try
                		// use an average over aa, bb, cc
                		dashes.put(Integer.valueOf(kBest),
                				new Dash(
                						kBest, jDot, jDash, sig,
                						(aa.a + bb.a + cc.a)/3, true));
                	}
                	catch (Exception e) {
                		new Info("cannot create dash, k: %d", kBest);
                	}
                }
            }
            prevD = r;
        }

        // check for clustering, merge doublets

        mergeClusters(dashes, jDot, jDash, mergeDashesWhenCloser, sig);


        // find all dots w/o worrying about dashes

        TwoDoubles prevDot = new TwoDoubles(0.0,  Double.MAX_VALUE);

        final NavigableMap<Integer, ToneBase> dots = new TreeMap<Integer, ToneBase>();

        // TODO: the exact calculation of limits can be refined maybe
        for (int k = 0 + 3*jDot; k < sigSize - 3*jDot; k++) {
            final TwoDoubles r = lsq(sig, k, jDot, wDot);

            //new Info("%10f %10f", r.a, r.b);
            if (prevDot.b >= 0.0 && r.b < 0.0) {

                final int kBest = prevDot.b > -r.b ? k : k-1;
                final double a = prevDot.b > -r.b ? r.a : prevDot.a;

//                final double t = 1+kBest*0.008005;
//                if (t > 276 && t < 276.2) {
//                    new Debug("dot candidate at t: %10f, k: %d, a: %10f", t, k, r.a);
//                    new Debug("jDot: %d, %f", jDot, jDot * 0.008005);
//                    for (int q = -5*jDot; q <= 5*jDot; q += jDot) {
//                        final double dt = q*0.008005;
//
//                        final TwoDoubles x = lsq(sig, k + q, jDot, wDot);
//                        new Debug("t: %f, ampl: %f", t+dt, x.a);
//                    }
//                }

                final TwoDoubles u1 = lsq(sig, kBest - 2*jDot, jDot, wDot);
                final TwoDoubles u2 = lsq(sig, kBest + 2*jDot, jDot, wDot);

                if (cei[kBest] - a < dotStrengthLimit &&

                        a - u1.a > dotPeakCrit &&
                        a - u2.a > dotPeakCrit

                        ) {
                    dots.put(Integer.valueOf(kBest), new Dot(kBest, kBest - jDot, kBest + jDot));
                }

            }
            prevDot = r;
        }

        mergeClusters(dots, jDot, jDash, mergeDotsWhenCloser, sig);

        // remove dashes if there are two competing dots

        final List<Integer> removals = new ArrayList<Integer>();
        for (Integer key : dashes.navigableKeySet()) {

            final Integer k1 = dots.lowerKey(key);
            final Integer k2 = dots.higherKey(key);

            if (k1 != null && k2 != null &&
                    key-jDash < k1 && k1 < key-jDot && key+jDot < k2 && k2 < key+jDash) {
                removals.add(key);
            }
        }

        // remove dots if there is already a dash
        removals.clear();
        for (Integer key : dots.navigableKeySet()) {
            final Integer k1 = dashes.floorKey(key);
            final Integer k2 = dashes.higherKey(key);

            if ((k1 != null && key - k1 < jDashSmall) || (k2 != null && k2 - key < jDashSmall)) {
                removals.add(key);
            }
        }

        for (Integer m : removals) {
            dots.remove(m);
        }

        // merge the dots to the dashes
        for (Integer key : dots.keySet()) {
            dashes.put(key, dots.get(key));
        }


        Node p = tree;
        int qCharBegin = -999999;
        Integer prevKey = null;
        for (Integer key : dashes.navigableKeySet()) {

            // TODO, the 4 is a decoder identifier
        	if (prevKey == null) {
        		qCharBegin = lsqToneBegin(key, dashes, jDot);
        	}
            if (prevKey != null && toneDist(prevKey, key, dashes, jDash, jDot) >
            WORD_SPACE_LIMIT[decoder]/tsLength) {
            	
            	//formatter.add(true, p.text, -1);
            	final int ts =
                        GerkeLib.getFlag(O_TSTAMPS) ?
                        offset + (int) Math.round(key*tsLength*tuMillis/1000) : -1;
                formatter.add(true, p.text, ts);
                chCus += p.nTus;
                spCusW += 7;
                spTicksW += lsqToneBegin(key, dashes, jDot) - lsqToneEnd(prevKey, dashes, jDot);
                
                p = tree;
                final ToneBase tb = dashes.get(key);
                if (tb instanceof Dash) {
                	p = p.newNode("-");
                }
                else {
                	p = p.newNode(".");
                }
                chTicks += lsqToneEnd(prevKey, dashes, jDot) - qCharBegin;
                qCharBegin = lsqToneBegin(key, dashes, jDot);
                lsqPlotHelper(plotEntries, plotLimits, key, tb, jDot, framesPerSlice, frameRate, offsetFrames, ceilingMax);
            }
            else if (prevKey != null && toneDist(prevKey, key, dashes, jDash, jDot) > CHAR_SPACE_LIMIT[decoder]/tsLength) {
                formatter.add(false, p.text, -1);
                chCus += p.nTus;
                spCusC += 3;
                
                spTicksC += lsqToneBegin(key, dashes, jDot) - lsqToneEnd(prevKey, dashes, jDot);

//                if (p.text.equals("+")) {
//                    new Info("\n    adding a '+'");
//
//                    ToneBase uu = dashes.get(prevKey);
//                    ToneBase vv = dashes.get(key);
//
//                    new Info("dist: %d, %d, %f", uu.k, vv.k, (vv.k-uu.k - jDash -jDot)*tsLength);
//                }

                p = tree;
                final ToneBase tb = dashes.get(key);
                if (tb instanceof Dash) {
                	p = p.newNode("-");
                }
                else {
                	p = p.newNode(".");
                }
                chTicks += lsqToneEnd(prevKey, dashes, jDot) - qCharBegin;
                qCharBegin = lsqToneBegin(key, dashes, jDot);
                lsqPlotHelper(plotEntries, plotLimits, key, tb, jDot, framesPerSlice, frameRate, offsetFrames, ceilingMax);
            }
            else {
            	final ToneBase tb = dashes.get(key);
            	p = tb instanceof Dash ? p.newNode("-") : p.newNode(".");  
            	lsqPlotHelper(plotEntries, plotLimits, key, tb, jDot, framesPerSlice, frameRate, offsetFrames, ceilingMax);
            }
            prevKey = key;
        }

        if (p != tree) {
            formatter.add(true, p.text, -1);
            formatter.newLine();
            chCus += p.nTus;
            chTicks += lsqToneEnd(prevKey, dashes, jDot) - qCharBegin;
        }

        wpmReport(chCus, chTicks, spCusW, spTicksW, spCusC, spTicksC, tuMillis, tsLength);
    }
    
    
    private static int lsqToneBegin(Integer key, NavigableMap<Integer, ToneBase> tones, int jDot) {
    	ToneBase tone = tones.get(key);
    	if (tone instanceof Dash) {
    		return ((Dash)tone).rise;
    	}
    	else {
    		final int dotReduction = (int) Math.round(70.0*jDot/100);
    		return tone.k - dotReduction;
    	}	
    }
    
    private static int lsqToneEnd(Integer key, NavigableMap<Integer, ToneBase> tones, int jDot) {
    	ToneBase tone = tones.get(key);
    	if (tone instanceof Dash) {
    		return ((Dash)tone).drop;
    	}
    	else {
    		final int dotReduction = (int) Math.round(70.0*jDot/100);
    		return tone.k + dotReduction;
    	}	
    }
    
    
    private static void lsqPlotHelper(PlotEntries plotEntries, double[] plotLimits, Integer key, ToneBase tb,
    		int jDot, int framesPerSlice, int frameRate, int offsetFrames, double ceilingMax) {
    	final int dotReduction = (int) Math.round(80.0*jDot/100);
    	if (plotEntries != null) {
    		if (tb instanceof Dot) {
    			final int kRise = key - dotReduction;
    			final int kDrop = key + dotReduction;
    			final double secRise1 = timeSeconds(kRise, framesPerSlice, frameRate, offsetFrames);
    			final double secRise2 = timeSeconds(kRise+1, framesPerSlice, frameRate, offsetFrames);
    			final double secDrop1 = timeSeconds(kDrop, framesPerSlice, frameRate, offsetFrames);
    			final double secDrop2 = timeSeconds(kDrop+1, framesPerSlice, frameRate, offsetFrames);
    			if (plotLimits[0] < secRise1 && secDrop2 < plotLimits[1]) {
    				plotEntries.addDecoded(secRise1, ceilingMax/20);
    				plotEntries.addDecoded(secRise2, 2*ceilingMax/20);
    				plotEntries.addDecoded(secDrop1, 2*ceilingMax/20);
    				plotEntries.addDecoded(secDrop2, ceilingMax/20);
    			}
    		}
        	else if (tb instanceof Dash) {
    			final int kRise = ((Dash) tb).rise;
    			final int kDrop = ((Dash) tb).drop;
    			final double secRise1 = timeSeconds(kRise, framesPerSlice, frameRate, offsetFrames);
    			final double secRise2 = timeSeconds(kRise+1, framesPerSlice, frameRate, offsetFrames);
    			final double secDrop1 = timeSeconds(kDrop, framesPerSlice, frameRate, offsetFrames);
    			final double secDrop2 = timeSeconds(kDrop+1, framesPerSlice, frameRate, offsetFrames);
    			if (plotLimits[0] < secRise1 && secDrop2 < plotLimits[1]) {
    				plotEntries.addDecoded(secRise1, ceilingMax/20);
    				plotEntries.addDecoded(secRise2, 2*ceilingMax/20);
    				plotEntries.addDecoded(secDrop1, 2*ceilingMax/20);
    				plotEntries.addDecoded(secDrop2, ceilingMax/20);
    			}
        	}
    	}
    }

    /**
     * It is trusted that 'tones' is dashes-only or dots-only
     * 
     * @param tones
     * @param jX
     * @param ticks
     */
    private static void mergeClusters(
            NavigableMap<Integer, ToneBase> tones,
            int jDot,
            int jDash,
            double ticks,
            double[] sig) {
        final List<Cluster> clusters = new ArrayList<Cluster>();
        for (Integer key : tones.navigableKeySet()) {
                if (clusters.isEmpty()) {
                    clusters.add(new Cluster(key));
                }
                else if (key - clusters.get(clusters.size() - 1).lowestKey < ticks) {
                    clusters.get(clusters.size() - 1).add(key);
                    boolean isDot = tones.get(key) instanceof Dot;
                    final String ss = isDot ? "dots" : "dashes";
                    new Debug("clustering %s at t: %f", ss, 0.008005*key);
                }
                else {
                    clusters.add(new Cluster(key));
                }
        }

        for (Cluster c : clusters) {
            if (c.members.size() > 1) {
                int kSum = 0;
                boolean isDot = false;
                
                int minRise = 0;
                int maxDrop = 0;
                double sumCeiling = 0.0;

                for (Integer kk : c.members) {
                	
                	final ToneBase tone =tones.get(kk); 
                	if (tone instanceof Dot) {
                		isDot = true;
                	}
                	else {
                		final Dash dash = ((Dash) tone);
                		minRise += Compute.iMin(minRise, dash.rise);
                		maxDrop += Compute.iMax(maxDrop, dash.drop);
                		sumCeiling += dash.ceiling;
                	}

                    tones.remove(kk);
                    kSum += kk.intValue();
                }
                final int newK = (int) Math.round(((double) kSum)/c.members.size());
                
                if (isDot) {
                	tones.put(Integer.valueOf(newK), new Dot(newK, newK - jDot, newK + jDot));
                }
                else {
                	tones.put(Integer.valueOf(newK),
                			new Dash(newK,
                					minRise,
                					maxDrop,
                					sig,
                					sumCeiling/c.members.size(), false));
                }
            }
        }
    }

    private static int toneDist(
            Integer k1,
            Integer k2,
            NavigableMap<Integer, ToneBase> tones,
            int jDash,
            int jDot) {

        final ToneBase t1 = tones.get(k1);
        final ToneBase t2 = tones.get(k2);

        int reduction = 0;

        final int dotReduction = (int) Math.round(80.0*jDot/100);
        
        if (t1 instanceof Dot) {
        	reduction += dotReduction;
        }
        else if (t1 instanceof Dash) {
        	reduction += ((Dash) t1).drop - ((Dash) t1).k;
        }
        
        if (t2 instanceof Dot) {
        	reduction += dotReduction;
        }
        else if (t2 instanceof Dash) {
        	reduction += ((Dash) t2).k - ((Dash) t2).rise;
        }

        return k2 - k1 - reduction;
    }
    
    private static int toneDist2(
            Integer k1,
            Integer k2,
            NavigableMap<Integer, ToneBase> tones,
            int jDash,
            int jDot) {

        final ToneBase t1 = tones.get(k1);
        final ToneBase t2 = tones.get(k2);

        
        int reduction = 0;
        
        if (t1 instanceof Dot) {
        	reduction += ((Dot) t1).drop - ((Dot) t1).k;
        }
        else if (t1 instanceof Dash) {
        	reduction += ((Dash) t1).drop - ((Dash) t1).k;
        }
        
        
        if (t2 instanceof Dot) {
        	reduction += ((Dot) t2).k - ((Dot) t2).rise;
        }
        else if (t2 instanceof Dash) {
        	reduction += ((Dash) t2).k - ((Dash) t2).rise;
        }

        return k2 - k1 - reduction;
    }

    private static TwoDoubles lsq(double[] sig, int k, int jMax, WeightBase weight) {

        double sumW = 0.0;
        double sumJW = 0.0;
        double sumJJW = 0.0;
        double r1 = 0.0;
        double r2 = 0.0;
        for (int j = -jMax; j <= jMax; j++) {
            final double w = weight.w(j);
            sumW += w;
            sumJW += j*w;
            sumJJW += j*j*w;
            r1 += w*sig[k+j];
            r2 += j*w*sig[k+j];
        }
        double det = sumW*sumJJW - sumJW*sumJW;

        return new TwoDoubles((r1*sumJJW - r2*sumJW)/det, (sumW*r2 - sumJW*r1)/det);
    }



    /**
     * Report WPM estimates
     * 
     * @param chCus             nominal nof. TUs in character
     * @param chTicks           actual nof. ticks in character
     * @param spCusW            nominal nof. TUs in word spaces (always increment by 7)
     * @param spTicksW          actual nof. ticks in word spaces
     * @param spCusC            nominal nof. TUs in char spaces (always increment by 3)
     * @param spTicksC          actual nof. ticks in char spaces
     * @param tuMillis
     * @param tsLength
     */
    private static void wpmReport(
            int chCus,
            int chTicks,
            int spCusW,
            int spTicksW,
            int spCusC,
            int spTicksC,
            double tuMillis,
            double tsLength) {

        if (chTicks > 0) {
            new Info("within-characters WPM rating: %.1f",
                    1200*chCus/(chTicks*tuMillis*tsLength));
        }
        if (spCusC > 0) {
            new Info("expansion of inter-char spaces: %.3f",
                    (spTicksC*tsLength)/spCusC);
        }
        if (spCusW > 0) {
            new Info("expansion of inter-word spaces: %.3f",
                    (spTicksW*tsLength)/spCusW);
        }
        if (spTicksW + spTicksC + chTicks > 0) {
            new Info("effective WPM: %.1f",
                    1200*(spCusW + spCusC + chCus)/((spTicksW + spTicksC + chTicks)*tuMillis*tsLength));
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
                    phasePlot ? TWO_PI*fBest*timeSeconds(q, framesPerSlice, w.frameRate, w.offsetFrames) : 0.0;

                    double sinAcc = 0.0;
                    double cosAcc = 0.0;
                    for (int j = 0; j < framesPerSlice; j++) {
                        // j is frame index
                        final int ampRaw = w.wav[q*framesPerSlice + j];
                        final int amp = ampRaw < 0 ?
                                Compute.iMax(-clipLevel, ampRaw) :
                                    Compute.iMin(clipLevel, ampRaw);

                                final double angle = angleOffset + TWO_PI*fBest*j/w.frameRate;
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

    public static double squared(double x) {
        return x*x;
    }

    private static double timeSeconds(int q, int framesPerSlice, int frameRate, int offsetFrames) {
        return (((double) q)*framesPerSlice + offsetFrames)/frameRate;
    }

    /**
     * Remove null elements from the given array. The returned value
     * is the new active size.
     *
     * @param trans
     * @param activeSize
     * @return
     */
    private static int removeHoles(Trans[] trans, int activeSize) {
        int k = 0;
        for (int i = 0; i < activeSize; i++) {
            if (trans[i] != null) {
                trans[k] = trans[i];
                k++;
            }
        }
        return k;
    }

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

    private static double localCeilByHist(int q, double[] sig, int width) {

        int q1 = Compute.iMax(q-width/2, 0);
        int q2 = Compute.iMin(q+width/2, sig.length);

        // find the maximal sig value
        double sigMax = -1.0;
        for (int j = q1; j < q2; j++) {
            sigMax = Compute.dMax(sig[j], sigMax);
        }

        // produce a histogram with HIST_SIZE_CEILING slots
        int sumPoints = 0;
        final int[] hist = new int[P_CEIL_HIST];
        for (int j = q1; j < q2; j++) {
            final int index = (int) Math.round((P_CEIL_HIST-1)*sig[j]/sigMax);
            int points = (int)Math.round(500/(1 + squared((j-q)/P_CEIL_FOCUS)));
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
     * @return
     */
    private static double localFloorByHist(int q, double[] sig, int width) {

        int q1 = Compute.iMax(q-width/2, 0);
        int q2 = Compute.iMin(q+width/2, sig.length);

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
            int points = (int)Math.round(500/(1 + squared((j-q)/P_FLOOR_FOCUS)));
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
