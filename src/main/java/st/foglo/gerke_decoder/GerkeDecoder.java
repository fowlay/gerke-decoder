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

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.CountDownLatch;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;

import st.foglo.gerke_decoder.GerkeDecoder.PlotCollector.Mode;
import st.foglo.gerke_decoder.GerkeLib.*;

public final class GerkeDecoder {
	
	static final int HI = 10;
	static final int LO = -35;
	
	static final double TWO_PI = 2*Math.PI;
	
	/**
	 * Tone/silence threshold per decoder. Values were determined by
	 * decoding some recordings with good signal strength. 
	 */
	static final double[] THRESHOLD = {-999.999, 0.524, 0.5634, 0.524};
	//static final double THRESHOLD = 0.5634;

	static final String O_VERSION = "version";
	static final String O_OFFSET = "offset";
	static final String O_LENGTH = "length";
	static final String O_FRANGE = "frange";
	static final String O_FREQ = "freq";
	static final String O_WPM = "wpm";
	static final String O_SPACE_EXP = "space-expansion";
	static final String O_CLIPPING = "clip";
	static final String O_STIME = "sample-time";
	static final String O_SIGMA = "sigma";
	static final String O_DECODER = "decoder";
	static final String O_LEVEL = "level";
	static final String O_TSTAMPS = "timestamps";
	static final String O_FPLOT = "frequency-plot";
	static final String O_PLINT = "plot-interval";
	static final String O_PLOT = "plot";
	static final String O_PPLOT = "phase-plot";
	static final String O_VERBOSE = "verbose";
	
	static final String O_HIDDEN = "hidden-options";
	enum HiddenOpts {DIP, SPIKE, BREAK_LONG_DASH, FILTER, CUTOFF, ORDER, PHASELOCKED, PLWIDTH};
	
	static final double WORD_SPACE_LIMIT = 5.1;    // words break <---------+---------> words stick
	static final double CHAR_SPACE_LIMIT = 1.75;   // chars break <---------+---------> chars cluster
	static final double DASH_LIMIT = 1.7;          // 1.6
	static final double TWO_DASH_LIMIT = 6.0;      // 6.0
	
	
	/**
	 * Unused for now. TODO
	 */
	static final String O_D3 = "decoder3-options";
	
	
	// Decoder-independent parameters
	
	/**
	 * Default level of clipping: Clipping is made at a level that reduces
	 * the total signal content by this fraction.
	 */
	static final double P_CLIP_STRENGTH = 0.05;
	
	/**
	 * Iterative search for a cliplevel is terminated when P_CLIP_STRENGTH
	 * is reached to an accuracy given by this parameter. To observe
	 * iterations, run with -v -v -v.
	 */
	static final double P_CLIP_PREC = 0.005;
	
	
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
	 * Dips of lesser strength are ignored. A too high value will cause
	 * weak dips to be ignored, so that 'i' prints as 't' for example.
	 */
	static final double P_DIP_STRENGTH_MIN = 1.3;
	
	/**
	 * Consider merging dips when closer than this. Unit is TUs.
	 */
	static final double P_DIP_MERGE_LIM = 0.6;
	
	/**
	 * Ideally the separation between dips is 4 halfTus for a dot
	 * and 8 halfTus for a dash. Increasing the value will cause
	 * dashes to be seen as dots.
	 */
	static final double P_DIP_DASHMIN = 5.55; // 6.1
	
	/**
	 * When generating a table of exponential weights, exclude entries
	 * lower than this limit.
	 */
	static final double P_DIP_EXPTABLE_LIM = 0.01;
	

	static {
		/**
		 * Note: Align with the top level pom.xml
		 */
		new VersionOption("V", O_VERSION, "gerke-decoder version 2.0 RC 2");

		new SingleValueOption("o", O_OFFSET, "0");
		new SingleValueOption("l", O_LENGTH, "-1");

		new SingleValueOption("F", O_FRANGE, "400,1200");
		new SingleValueOption("f", O_FREQ, "-1");
		
		new SingleValueOption("w", O_WPM, "15.0");
		new SingleValueOption("W", O_SPACE_EXP, "1.0");

		new SingleValueOption("c", O_CLIPPING, "-1");
		new SingleValueOption("q", O_STIME, "0.10");
		new SingleValueOption("s", O_SIGMA, "0.25");

		new SingleValueOption("D", O_DECODER, "3");

		new SingleValueOption("u", O_LEVEL, "1.0");
		new Flag("t", O_TSTAMPS);

		new Flag("S", O_FPLOT);
		
		new SingleValueOption("Z", O_PLINT, "0,-1");
		new Flag("A", O_PLOT);
		new Flag("P", O_PPLOT);

		new SteppingOption("v", O_VERBOSE);

		new SingleValueOption("H", O_HIDDEN,
				         "0.002"+                   // dip removal
		                ",0.005"+                   // spike removal
				        ",1"+                       // break too long dashes
						",b"+                       // b: Butterworth, cI: Chebyshev I, w: sliding Window, n: No filter
						",2.0"+                     // frequency, relative to 1/TU
						",2"+                       // filter order
						",0"+                       // phase-locked: 0=off, 1=on
						",0.8"                      // phase averaging, relative to TU
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
		String.format("  -D DECODER         1: Tone/silence, 2: Pattern matching, 3: Dips finding, defaults to %s",
				GerkeLib.getDefault(O_DECODER)),
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
	
	/**
	 * Read a WAV file into a short[] array.
	 */
	static class Wav {
		
		final String file;   // file path
		
		final AudioInputStream ais;
		final AudioFormat af;
		final long frameLength;
		
		final int frameRate; // frames/s
		final int offset;    // offset (s)
		final int offsetFrames;   // offset as nof. frames
		final int length;    // length (s)
		final short[] wav;   // signal values
		
		final int nofFrames;

		public Wav() throws IOException, UnsupportedAudioFileException {
			
			if (GerkeLib.nofArguments() != 1) {
				new Death("expecting one filename argument, try -h for help");
			}
			file = GerkeLib.getArgument(0);
			
			ais = AudioSystem.getAudioInputStream(new File(file));
			af = ais.getFormat();
			new Info("audio format: %s", af.toString());
			
			frameRate = Math.round(af.getFrameRate());
			new Info("frame rate: %d", frameRate);
			
			final int nch = af.getChannels();  // needed in reading, localize?
			new Info("nof. channels: %d", nch);
			final int bpf = af.getFrameSize();
			if (bpf == AudioSystem.NOT_SPECIFIED) {
				new Death("bytes per frame is NOT SPECIFIED");
			} 
			else {
				new Info("bytes per frame: %d", bpf);
			}
			if (af.isBigEndian()) {
				new Death("cannot handle big-endian WAV file");
			}
			
			
			frameLength = ais.getFrameLength();
			new Info(".wav file length: %.1f s", (double)frameLength/frameRate);
			new Info("nof. frames: %d", frameLength);
			
			// find out nof frames to go in array
			
			offset = GerkeLib.getIntOpt(O_OFFSET);
			length = GerkeLib.getIntOpt(O_LENGTH);
			
			if (offset < 0) {
				new Death("offset cannot be negative");
			}
			
			offsetFrames = offset*frameRate;
			
			final int offsetFrames = GerkeLib.getIntOpt(O_OFFSET)*frameRate;
			nofFrames = length == -1 ? ((int) (frameLength - offsetFrames)) : length*frameRate;
			
			if (nofFrames < 0) {
				new Death("offset too large, WAV file length is: %f s", (double)frameLength/frameRate);
			}


			wav = new short[nofFrames];
			
			int frameCount = 0;
			
			if (bpf == 1 && nch == 1) {
				final int blockSize = frameRate;
				for (int k = 0; true; k += blockSize) {
					final byte[] b = new byte[blockSize];
					final int nRead = ais.read(b, 0, blockSize);
					if (k >= offsetFrames) {
						if (nRead == -1 || frameCount == nofFrames) {
							break;
						}
						for (int j = 0; j < nRead && frameCount < nofFrames; j++) {
							wav[k - offsetFrames + j] = (short) (100*b[j]);
							frameCount++;
						}
					}
				}
			}
			else if (bpf == 2 && nch == 1) {
				final int blockSize = bpf*frameRate;
				for (int k = 0; true; k += blockSize) {
					final byte[] b = new byte[blockSize];
					final int nRead = ais.read(b, 0, blockSize);
					if (k >= offsetFrames*bpf) {
						if (nRead == -1 || frameCount == nofFrames) {
							break;
						}
						for (int j = 0; j < nRead/bpf && frameCount < nofFrames; j++) {
							wav[k/bpf - offsetFrames + j] =
									(short) (256*b[bpf*j+1] + (b[bpf*j] < 0 ? (b[bpf*j] + 256) : b[bpf*j]));
							frameCount++;
						}
					}
				}
			}
			else if (bpf == 4 && nch == 2) {
				final int blockSize = bpf*frameRate;
				for (int k = 0; true; k += blockSize) {
					final byte[] b = new byte[blockSize];
					final int nRead = ais.read(b, 0, blockSize);
					if (k >= offsetFrames*bpf) {
						if (nRead == -1 || frameCount == nofFrames) {
							break;
						}
						for (int j = 0; j < nRead/bpf && frameCount < nofFrames; j++) {
							final int left = (256*b[bpf*j+1] + (b[bpf*j] < 0 ? (b[bpf*j] + 256) : b[bpf*j]));
							final int right = (256*b[bpf*j+3] + (b[bpf*j+2] < 0 ? (b[bpf*j+2] + 256) : b[bpf*j+2]));
							wav[k/bpf - offsetFrames + j] =
									(short) ((left + right)/2);
							frameCount++;
						}
					}
				}
			}
			else {
				ais.close();
				new Death("cannot handle bytesPerFrame: %d, nofChannels: %d", bpf, nch);
			}
			ais.close();
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
	
	
	static abstract class FilterRunnerBase implements Runnable {
		final LowpassFilter f;
		final short[] wav;
		final double[] out;

		final int framesPerSlice;
		final int clipLevel;

		final int freq;
		final int frameRate;
		final double phaseShift;
		
		final double tsLength;

		final CountDownLatch cdl;
		
		public FilterRunnerBase(LowpassFilter f, short[] wav, double[] out,
				int framesPerSlice,
				int clipLevel,
				int freq,
				int frameRate,
				double phaseShift,
				CountDownLatch cdl,
				double tsLength) {
			this.f = f;
			this.wav = wav;
			this.out = out;

			this.framesPerSlice = framesPerSlice;
			this.clipLevel = clipLevel;

			this.freq = freq;
			this.frameRate = frameRate;

			this.phaseShift = phaseShift;

			this.cdl = cdl;
			
			this.tsLength = tsLength;
		}
		
	}
	
	
	static class FilterRunnerZero extends FilterRunnerBase {

		public FilterRunnerZero(LowpassFilter f, short[] wav, double[] out,
				int framesPerSlice,
				int clipLevel,
				int freq,
				int frameRate,
				double phaseShift,
				CountDownLatch cdl) {
			super(f, wav, out,
					framesPerSlice,
					clipLevel,
					freq,
					frameRate,
					phaseShift,
					cdl,
					999.999);
		}

		@Override
		public void run() {
			
			for (int q = 0; true; q++) {      //  q is out[] index

				if (wav.length - q*framesPerSlice < framesPerSlice) {
					break;
				}
				out[q] = 0.0;
			}
			
			if (cdl != null) {
				cdl.countDown();
			}
		}
	}
	
	static class FilterRunnerPhaseLocked extends FilterRunnerBase {
		
		final int nPhaseAvg;

		public FilterRunnerPhaseLocked(LowpassFilter f, short[] wav, double[] out,
				int framesPerSlice,
				int clipLevel,
				int freq,
				int frameRate,
				double phaseShift,
				CountDownLatch cdl,
				double tsLength) {
			super(f, wav, out,
					framesPerSlice,
					clipLevel,
					freq,
					frameRate,
					phaseShift,
					cdl,
					tsLength);
			nPhaseAvg = roundToOdd(GerkeLib.getDoubleOptMulti(O_HIDDEN)[HiddenOpts.PLWIDTH.ordinal()]*(1/tsLength));
			new Info("nPhaseAvg: %d", nPhaseAvg);
		}

		@Override
		public void run() {
			
			for (int q = 0; true; q++) {      //  q is out[] index

				if (wav.length - q*framesPerSlice < framesPerSlice) {
					break;
				}

				final double phase =
						smoothedPhase(nPhaseAvg, q, wav, frameRate, framesPerSlice, clipLevel, freq);
				
				double sum = 0.0;
				double outSignal;
				for (int k = -framesPerSlice+1; k <= 0; k++) {
					final int wavIndex = q*framesPerSlice + k;

					if (wavIndex >= 0) {
						int ampRaw = wav[wavIndex];   // k is non-positive!
						final int amp = ampRaw < 0 ? iMax(-clipLevel, ampRaw) : iMin(clipLevel, ampRaw);
						final double angle = ((freq*wavIndex)*TWO_PI)/frameRate;
						sum += Math.sin(angle - phase)*amp;
					}
					outSignal = f.filter(sum);
					if (k == 0) {
						out[q] = outSignal;
						f.reset();
					}
				}
			} // iterate over q

			if (cdl != null) {
				cdl.countDown();
			}
		}

		private double smoothedPhase(
				int m,
				int q,
				short[] wav,
				int frameRate,
				int framesPerSlice,
				int clipLevel,
				int freq) {


			double sinSum = 0.0;
			double cosSum = 0.0;
			// example: m = 5 =>
			// sum over j=-2, -1, 0, 1, 2
			for (int j = 0; j <= m/2; j++) {
				for (int sgn = -1; sgn <= 1; sgn += (2 + (j == 0 ? 99 : 0))) {
					
					for (int k = -framesPerSlice+1; k <= 0; k++) {
						final int wavIndex = q*framesPerSlice + k + (j*sgn*framesPerSlice);

						if (wavIndex >= 0 && wavIndex < wav.length) {
							int ampRaw = wav[wavIndex];   // k is non-positive!
							final int amp = ampRaw < 0 ? iMax(-clipLevel, ampRaw) : iMin(clipLevel, ampRaw);
							
							final double angle = ((freq*wavIndex)*TWO_PI)/frameRate;
							
							final double sinValue = Math.sin(angle+phaseShift);
							final double cosValue = Math.cos(angle+phaseShift);
							
							sinSum += sinValue*amp;
							cosSum += cosValue*amp;
						}
					}
				}
			}

			return Math.atan2(-cosSum, sinSum);
		}
	}
	
		
		
	static class FilterRunner extends FilterRunnerBase {

		public FilterRunner(LowpassFilter f, short[] wav, double[] out,
				int framesPerSlice,
				int clipLevel,
				int freq,
				int frameRate,
				double phaseShift,
				CountDownLatch cdl) {
			super(f, wav, out,
					framesPerSlice,
					clipLevel,
					freq,
					frameRate,
					phaseShift,
					cdl,
					999.999);
		}

		@Override
		public void run() {
			
			final ArrayList<Double> sinTable = new ArrayList<Double>(); sinTable.clear();
			int tableSize = -1;
			boolean useTable = false;
			int index = -1;
			double firstAngle = -999999.9;
			boolean firstAngleDefined = false;
			
			for (int q = 0; true; q++) {      //  q is out[] index

				if (wav.length - q*framesPerSlice < framesPerSlice) {
					break;
				}

				// feed the filters with wav samples
				for (int k = -framesPerSlice+1; k <= 0; k++) {

					final int wavIndex = q*framesPerSlice + k;

					double outSignal = 0.0;
					if (wavIndex >= 0) {
						int ampRaw = wav[wavIndex];   // k is non-positive!
						final int amp = ampRaw < 0 ? iMax(-clipLevel, ampRaw) : iMin(clipLevel, ampRaw);
						final double sinValue;
						if (!useTable) {
							double angle = ((freq*wavIndex)*TWO_PI)/frameRate;
							if (!firstAngleDefined) {
								firstAngle = angle;
								firstAngleDefined = true;
								sinValue = Math.sin(angle+phaseShift);
								index = 0;
								sinTable.add(new Double(sinValue));
							}
							else {
								double laps = (angle - firstAngle)/TWO_PI;
								// PARAMETER 0.005, not critical, introduces phase jitter < 0.3 degrees
								if (laps > 0.5 && Math.abs(laps - Math.round(laps)) < 0.005) {
									useTable = true;
									index++;
									tableSize = index;
									new Info("sine table size: %d", sinTable.size());
									sinValue = sinTable.get(0).doubleValue();
								}
								else {
									sinValue = Math.sin(angle+phaseShift);
									index++;
									sinTable.add(new Double(sinValue));
								}
							}
						}
						else {
							index++;
							sinValue = sinTable.get(index % tableSize).doubleValue();
						}
						outSignal = f.filter(amp*sinValue);
					}

					if (k == 0) {
						out[q] = outSignal;
						f.reset();
					}
				}
			}
			if (cdl != null) {
				cdl.countDown();
			}
		}
	}

	static class TrigTable {
		final double[] sines;
		final double[] coses;
		
		TrigTable(int f, int nFrames, int frameRate) {
			this.sines = new double[nFrames];
			this.coses = new double[nFrames];
			for (int j = 0; j < nFrames; j++) {
				final double angle = TWO_PI*f*j/frameRate;
				sines[j] = Math.sin(angle);
				coses[j] = Math.cos(angle);
			}
		}
		
		double sin(int j) {
			return sines[j];
		}
		
		double cos(int j) {
			return coses[j];
		}
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
	
	/**
	 * Represents a diagram to be plotted. Interface is:
	 * 
	 * ps          - a print stream for line-by-line data
	 * plot()      - create the diagram
	 */
	static class PlotCollector {
		
		enum Mode {
			LINES_PURPLE("lines ls 1"),
			LINES_GREEN("lines ls 2"),
			LINES_CYAN("lines ls 3"),
			LINES_GOLD("lines ls 4"),
			LINES_YELLOW("lines ls 5"),
			LINES_BLUE("lines ls 6"),
			LINES_RED("lines ls 7"),
			LINES_BLACK("lines ls 8"),
			POINTS("points");

			String s;

			Mode(String s) {
				this.s = s;
			}
		};
		
		private String fileName;
		private String fileNameWin;
		final PrintStream ps;
		
		PlotCollector() throws IOException {

			this.fileName = makeTempFile();
			this.fileNameWin = isWindows() ? toWindows(fileName) : null;
			this.ps = new PrintStream(
					new File(fileNameWin != null ? fileNameWin : fileName));
		}
		
		void plot(Mode mode[], int nofCurves) throws IOException, InterruptedException {
			ps.close();
			doGnuplot(fileName, nofCurves, mode);
			Files.delete(
					(new File(fileNameWin != null ? fileNameWin : fileName)).toPath());
		}
		
		/**
		 * Invoke Gnuplot
		 * 
		 * @param tempFileName
		 * @param nofCurves    1|2
		 * @param mode         LINES|POINTS
		 * @throws IOException
		 * @throws InterruptedException
		 */
		void doGnuplot(
				String tempFileName,
				int nofCurves,
				Mode[] mode) throws IOException, InterruptedException {
			final ProcessBuilder pb =
					new ProcessBuilder(
							isWindows() ? "gnuplot-X11" : "gnuplot",
							"--persist",
							"-e",
							"set term x11 size 1400 200",
							"-e",
							nofCurves == 5 ? 
									String.format("plot '%s' using 1:2 with %s, '%s' using 1:3 with %s, '%s' using 1:4 with %s, '%s' using 1:5 with %s, '%s' using 1:6 with %s",
											tempFileName,
											mode[0].s,
											tempFileName,
											mode[1].s,
											tempFileName,
											mode[2].s,
											tempFileName,
											mode[3].s,
											tempFileName,
											mode[4].s) :
							
							
							nofCurves == 4 ? 
									String.format("plot '%s' using 1:2 with %s, '%s' using 1:3 with %s, '%s' using 1:4 with %s, '%s' using 1:5 with %s",
											tempFileName,
											mode[0].s,
											tempFileName,
											mode[1].s,
											tempFileName,
											mode[2].s,
											tempFileName,
											mode[3].s) :
							
							
							nofCurves == 3 ? 
									String.format("plot '%s' using 1:2 with %s, '%s' using 1:3 with %s, '%s' using 1:4 with %s",
											tempFileName,
											mode[0].s,
											tempFileName,
											mode[1].s,
											tempFileName,
											mode[2].s) :
							nofCurves == 2 ? 
									String.format("plot '%s' using 1:2 with %s, '%s' using 1:3 with %s",
											tempFileName,
											mode[0].s,
											tempFileName,
											mode[1].s) :
							        String.format("plot '%s' using 1:2 with %s",
											tempFileName,
											mode[0].s)
							);
			pb.inheritIO();
			final Process pr = pb.start();
			final int exitCode = pr.waitFor();
			new Debug("gnuplot exited with code: %d", exitCode);
		}
		
		String toWindows(String tempFileName) throws IOException {
			final ProcessBuilder pb = new ProcessBuilder("cygpath", "-w", tempFileName);
			final Process pr = pb.start();
			final InputStream is = pr.getInputStream();
			for (StringBuilder sb = new StringBuilder(); true; ) {
				final int by = is.read();
				if (by == -1) {
					return sb.toString();
				}
				else {
					final char c = (char)by;
					if (c != '\r' && c != '\n') {
						sb.append(c);
					}
				}
			}
		}
		

		String makeTempFile() throws IOException {
			final ProcessBuilder pb = new ProcessBuilder("mktemp");
			final Process pr = pb.start();
			final InputStream is = pr.getInputStream();
			for (StringBuilder sb = new StringBuilder(); true; ) {
				final int by = is.read();
				if (by == -1) {
					return sb.toString();
				}
				else {
					final char c = (char)by;
					if (c != '\r' && c != '\n') {
						sb.append(c);
					}
				}
			}
		}

		boolean isWindows() {
			return System.getProperty("os.name").startsWith("Windows");
		}
	}
	
	/**
	 * Collection of plot entries.
	 */
	static class PlotEntries {
		final SortedMap<Double, List<PlotEntryBase>> entries = new TreeMap<Double, List<PlotEntryBase>>();
		
		void addAmplitudes(double t, double amp, double threshold, double ceiling, double floor) {
			// assumes that this always happens before decoding
			final List<PlotEntryBase> list = new ArrayList<PlotEntryBase>();
			list.add(new PlotEntrySig(amp, threshold, ceiling, floor));
			entries.put(new Double(t), list);
		}
		
		void addDecoded(double t, double y) {
			final Double tBoxed = new Double(t);
			final List<PlotEntryBase> list = entries.get(tBoxed);
			if (list == null) {
				final List<PlotEntryBase> newList = new ArrayList<PlotEntryBase>();
				newList.add(new PlotEntryDecode(y));
				entries.put(tBoxed, newList);
			}
			else {
				list.add(new PlotEntryDecode(y));
			}
		}
	}
	
	
	/**
	 * Represents a single value or multiple values to be plotted.
	 */
	static abstract class PlotEntryBase{
	}
	
	/**
	 * Multiple value for plotting the signal.
	 */
	static final class PlotEntrySig extends PlotEntryBase {

		final double sig;
		final double threshold;
		final double ceiling;
		final double floor;
		
		public PlotEntrySig(double sig, double threshold, double ceiling, double floor) {
			this.sig = sig;
			this.threshold = threshold;
			this.ceiling = ceiling;
			this.floor = floor;
		}
	}
	
	/**
	 * Single value for plotting a decoded signal.
	 */
	static final class PlotEntryDecode extends PlotEntryBase {
		
		static final double height = 0.05;
		
		final double dec;

		public PlotEntryDecode(double dec) {
			this.dec = dec;
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
			final Integer sizeKey = new Integer(size);
			
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
	
	/**
	 * TODO: Improve detection by making use of level crossings within the
	 * character as well?
	 */
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
	
	static class TimeCounter {
		int chCus = 0;
		int chTicks = 0;
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
			
			final double tsLengthGiven = GerkeLib.getDoubleOpt(O_STIME);
			
			final int framesPerSlice = (int) Math.round(tsLengthGiven*w.frameRate*tuMillis/1000.0);
			
			final double tsLength = 1000.0*framesPerSlice/(w.frameRate*tuMillis);
			
			new Info("time slice: %.3f ms", 1000.0*framesPerSlice/w.frameRate);
			new Info("frames per time slice: %d", framesPerSlice);
			new Debug("time slice roundoff: %e", (tsLength - tsLengthGiven)/tsLengthGiven);

			
			// ===================================== Estimate frequency, or use given

			final int fBest;
			if (GerkeLib.getIntOpt(O_FREQ) != -1) {
				fBest = GerkeLib.getIntOpt(O_FREQ);
				new Info("specified frequency: %d", fBest);
				if (GerkeLib.getFlag(O_FPLOT)) {
					new Warning("frequency plot skipped when -f option given");
				}
			}
			else {
				// no frequency specified, search for best value
				fBest = findFrequency(framesPerSlice, w);
				new Info("estimated frequency: %d", fBest);
			}

			

			// ========================== Find clip level
			// TODO, could clipping be applied once and for all, after
			// a certain point has been passed?

			final int clipLevelOverride = GerkeLib.getIntOpt(O_CLIPPING);
			
			final int clipLevel =
					clipLevelOverride != -1 ? clipLevelOverride : getClipLevel(fBest, framesPerSlice, w);
			new Info("clipping level: %d", clipLevel);

			
			
			
			
			// ============  Multiply by sine and cosine functions, apply filtering

			final int nofSlices = w.nofFrames/framesPerSlice;
			final double[] sig = new double[nofSlices];

			final LowpassFilter filterI;
			final LowpassFilter filterQ;
			final String filterCode = GerkeLib.getOptMulti(O_HIDDEN)[HiddenOpts.FILTER.ordinal()];
			if (filterCode.equals("b")) {
				final int order = GerkeLib.getIntOptMulti(O_HIDDEN)[HiddenOpts.ORDER.ordinal()];
				double cutoff =
						GerkeLib.getDoubleOptMulti(O_HIDDEN)[HiddenOpts.CUTOFF.ordinal()];
				filterI = new LowpassButterworth(order, (double)w.frameRate, cutoff* 1000.0/tuMillis, 0.0);
				filterQ = new LowpassButterworth(order, (double)w.frameRate, cutoff* 1000.0/tuMillis, 0.0);
			}
			else if (filterCode.equals("cI")) {
				final int order = GerkeLib.getIntOptMulti(O_HIDDEN)[HiddenOpts.ORDER.ordinal()];
				double cutoff =
						GerkeLib.getDoubleOptMulti(O_HIDDEN)[HiddenOpts.CUTOFF.ordinal()];
				// PARAMETER 2.0 dB, ripple
				filterI = new LowPassChebyshevI(order, (double)w.frameRate, cutoff* 1000.0/tuMillis, 1.5);
				filterQ = new LowPassChebyshevI(order, (double)w.frameRate, cutoff* 1000.0/tuMillis, 1.5);
			}
			else if (filterCode.equals("w")) {
				double cutoff =
						GerkeLib.getDoubleOptMulti(O_HIDDEN)[HiddenOpts.CUTOFF.ordinal()];
				filterI = new LowpassWindow(w.frameRate, cutoff* 1000.0/tuMillis);
				filterQ = new LowpassWindow(w.frameRate, cutoff* 1000.0/tuMillis);
			}
			else if (filterCode.equals("t")) {
				filterI = new LowpassTimeSliceSum(framesPerSlice);
				filterQ = new LowpassTimeSliceSum(framesPerSlice);
			}
			else if (filterCode.equals("n")) {
				filterI = new LowpassNone();
				filterQ = new LowpassNone();
			}
			else {
				new Death("no such filter supported: '%s'", filterCode);
				throw new Exception();
			}

			final double[] outSin = new double[nofSlices];
			final double[] outCos = new double[nofSlices];
			
			final long tBegin = System.currentTimeMillis();
			final CountDownLatch cdl = new CountDownLatch(2);
			if (GerkeLib.getIntOptMulti(O_HIDDEN)[HiddenOpts.PHASELOCKED.ordinal()] == 1) {
				(new Thread(
						new FilterRunnerPhaseLocked(
								filterI,
								w.wav,
								outSin,
								framesPerSlice,
								clipLevel, fBest,
								w.frameRate,
								0.0,
								cdl,
								tsLength))).start();
			}
			else {
				(new Thread(
						new FilterRunner(
								filterI,
								w.wav,
								outSin,
								framesPerSlice,
								clipLevel, fBest,
								w.frameRate,
								0.0,
								cdl))).start();
			}

			if (GerkeLib.getIntOptMulti(O_HIDDEN)[HiddenOpts.PHASELOCKED.ordinal()] == 1) {
				(new Thread(
						new FilterRunnerZero(
								filterQ,
								w.wav,
								outCos,
								framesPerSlice,
								clipLevel, fBest,
								w.frameRate,
								Math.PI/2,
								cdl))).start();
			}
			else {
				(new Thread(
						new FilterRunner(
								filterQ,
								w.wav,
								outCos,
								framesPerSlice,
								clipLevel, fBest,
								w.frameRate,
								Math.PI/2,
								cdl))).start();
			}

			cdl.await();
			
			final double sigma = GerkeLib.getDoubleOpt(O_SIGMA);
			final double eps = 0.01; // PARAMETER eps
			
			final int gaussSize = roundToOdd((sigma/tsLength)*Math.sqrt(-2*Math.log(eps)));
			new Debug("nof. gaussian terms: %d", gaussSize);

			final double[] ringBuffer = new double[gaussSize];
			final double[] expTable = new double[gaussSize];

			for (int j = 0; j < gaussSize; j++) {
				int m = (gaussSize-1)/2;
				expTable[j] = Math.exp(-squared((j-m)*tsLength/sigma)/2);
			}

			for (int q = 0; true; q++) {      //  q is sig[] index

				if (w.wav.length - q*framesPerSlice < framesPerSlice) {
					break;
				}
				
	    		int ringIndex = q % gaussSize;
	    		ringBuffer[ringIndex] = Math.sqrt(outSin[q]*outSin[q] + outCos[q]*outCos[q]);
	    		int rr = ringIndex;
	    		double ss = 0.0;
	    		for (int ii = 0; ii < gaussSize; ii++) {
	    			ss += expTable[ii]*ringBuffer[rr];
	    			rr = rr+1 == gaussSize ? 0 : rr+1;
	    		}
	    		sig[q] = ss/gaussSize;
			}
			new Info("filtering took ms: %d", System.currentTimeMillis() - tBegin);
			
			
			
			// ================= Determine floor and ceiling. These arrays are used for
			// decoding and plotting as well.
			
			// 
			final int estBaseCeil = ensureEven((int)(P_CEIL_HIST_WIDTH*(tuMillis/1000.0)*w.frameRate/framesPerSlice));
			new Debug("ceiling estimation based on slices: %d", estBaseCeil);

			final int estBaseFloor = ensureEven((int)(P_FLOOR_HIST_WIDTH*(tuMillis/1000.0)*w.frameRate/framesPerSlice));
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
				ceilingMax = dMax(ceilingMax, cei[q]);
			}
			
			
			
			
			
			
			

			// some of this is used by the phase plot

			final int dashLimit = (int) Math.round(DASH_LIMIT*tuMillis*w.frameRate/(1000*framesPerSlice));        // PARAMETER
			final int wordSpaceLimit = (int) Math.round(WORD_SPACE_LIMIT*tuMillis*w.frameRate/(1000*framesPerSlice));   // PARAMETER
			final int charSpaceLimit = (int) Math.round(CHAR_SPACE_LIMIT*tuMillis*w.frameRate/(1000*framesPerSlice));   // PARAMETER
			final int twoDashLimit = (int) Math.round(TWO_DASH_LIMIT*tuMillis*w.frameRate/(1000*framesPerSlice));     // PARAMETER
			final double level = GerkeLib.getDoubleOpt(O_LEVEL);
			new Info("relative tone/silence threshold: %.3f", level);
			new Debug("dash limit: %d, word space limit: %d", dashLimit, wordSpaceLimit);

			
			final double saRaw = signalAverage(fBest, framesPerSlice, Short.MAX_VALUE, w);
			new Debug("signal average: %f", saRaw);
			final double sa = signalAverage(fBest, framesPerSlice, clipLevel, w);
			new Debug("signal average clipped: %f", sa);
			

			
			final int decoder = GerkeLib.getIntOpt(O_DECODER);
			
			
			// ================ Phase plot, optional
			
			if (GerkeLib.getFlag(O_PPLOT)) {
				phasePlot(fBest, nofSlices, framesPerSlice, w, clipLevel, sig, level, flo, cei, decoder);
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

				final double threshold = threshold(level*THRESHOLD[decoder], flo[q], cei[q]);
				thresholdMax = dMax(threshold, thresholdMax);
				
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
						final double threshold = threshold(level*THRESHOLD[decoder], flo[q], cei[q]);
						plotEntries.addAmplitudes(seconds, sig[q], threshold, cei[q], flo[q]);
					}
				}
			}

					
					
			if (decoder == 1) {
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
			else if (decoder == 2) {
				decodeByPatterns(
						formatter,
						plotEntries,
						trans,
						transIndex,
						sig,
						ceilingMax,
						tsLength, tuMillis,
						GerkeLib.getDoubleOpt(O_LEVEL),
						wordSpaceLimit,
						charSpaceLimit,
						framesPerSlice, w.frameRate, w.offsetFrames, plotLimits, offset);
			}
			else if (decoder == 3) {
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

				if (decoder > 3) {
					pc.plot(new Mode[] {Mode.LINES_PURPLE, Mode.LINES_RED, Mode.LINES_GREEN, Mode.LINES_GREEN}, 4);
				}
				else {
					pc.plot(new Mode[] {Mode.LINES_PURPLE, Mode.LINES_RED, Mode.LINES_GREEN, Mode.LINES_GREEN, Mode.LINES_CYAN}, 5);
				}
				

			}
			
		}
		catch (Exception e) {
			new Death(e);
		}
	}
	
	
	/**
	 * TODO, no reason to have 'long' parameters
	 * 
	 * TODO, dashlimit is only used here?
	 * 
	 * @param trans
	 * @param transIndex
	 * @param tuMillis
	 * @param tsLength
	 * @param wordSpaceLimit
	 * @param charSpaceLimit
	 * @throws IOException 
	 * @throws NoSuchAlgorithmException 
	 * @throws InterruptedException 
	 */
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
		
		int spTicks = 0;    // spaces: time-slice ticks
		int spCus = 0;      // spaces: code units
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

					spTicks += trans[t].q - trans[t-1].q;
					spCus += 7;
					chTicks += trans[t-1].q - qCharBegin;
					chCus += p.nTus;
					qCharBegin = trans[t].q;

					p = tree;
				}
				else if (trans[t].q - trans[t-1].q > charSpaceLimit) {
					formatter.add(false, p.text, -1);

					spTicks += trans[t].q - trans[t-1].q;
					spCus += 3;
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
		
		wpmReport(chCus, chTicks, spCus, spTicks, tuMillis, tsLength);
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
			Formatter formatter,
			PlotEntries plotEntries,
			Trans[] trans, int transIndex, double[] sig,
			double ceilingMax,
			double tsLength,
			double tuMillis,
			double level,
			int wordSpaceLimit,
			int charSpaceLimit,
			int framesPerSlice,
			int frameRate,
			int offsetFrames,
			double[] plotLimits,
			int offset) throws Exception {

		// PARAMETER 1.15
		// used for optimizing the integrated pattern matching
		// this setting was calibrated against some recordings with good signal strength
		//final double xLevel = 1.15;
		final double xLevel = 1.15;

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
						final CharTemplate ct = decodeChar(cd, sig, level, xLevel, tsLength, offset, tuMillis);
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

	

	private static CharTemplate decodeChar(CharData cd, double[] sig, double level, double xLevel,
			double tsLength, int offset, double tuMillis) {
		
		final int decoder = 2;
		
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

					// xLevel is taken from caller
					double u = sig[q] - threshold(xLevel*level*THRESHOLD[decoder], floor, ceiling);
					
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
		
		if (plotEntries != null) {
			// make one "decode" entry at left edge of plot
			plotEntries.addDecoded(plotLimits[0], PlotEntryDecode.height*ceilingMax);
		}

		int charNo = 0;

		// find characters
		// this is similar to what the level decoder does
		boolean prevTone = false;
		int beginChar = -1;
		
		int spTicks = 0;    // spaces: time-slice ticks
		int spCus = 0;      // spaces: code units
		
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
					decodeGapChar(beginChar, trans[t-1].q, sig, cei, flo, tsLength, charNo++,
							formatter, plotEntries, ceilingMax, framesPerSlice, frameRate, offsetFrames,
							plotLimits,
							tc);
					final int ts =
							GerkeLib.getFlag(O_TSTAMPS) ? 
							offset + (int) Math.round(trans[t].q*tsLength*tuMillis/1000) : -1;
					formatter.add(true, "", ts);
					beginChar = trans[t].q;
					spCus += 7;
					spTicks += trans[t].q - trans[t-1].q;
				}
				else if (trans[t].q - trans[t-1].q > spExp*charSpaceLimit) {
					decodeGapChar(beginChar, trans[t-1].q, sig, cei, flo, tsLength, charNo++,
							formatter, plotEntries, ceilingMax, framesPerSlice, frameRate, offsetFrames,
							plotLimits,
							tc);
					beginChar = trans[t].q;
					spCus += 3;
					spTicks += trans[t].q - trans[t-1].q;
				}
			}
			
			prevTone = newTone;
		}
		// prevTone is presumably a fall; if so, use it for the very last character
		// else the last char is incomplete; lose it
		if (trans[transIndex-1].rise == false) {
			decodeGapChar(beginChar, trans[transIndex-1].q, sig, cei, flo, tsLength, charNo++,
					formatter, plotEntries, ceilingMax, framesPerSlice, frameRate, offsetFrames,
					plotLimits,
					tc);
		}
		
		formatter.flush();
		formatter.newLine();

		wpmReport(tc.chCus, tc.chTicks, spCus, spTicks, tuMillis, tsLength);
	}

	private static void decodeGapChar(
			int q1,
			int q2,
			double[] sig,
			double[] cei,
			double[] flo,
			double tau,
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
		final double[] strength = new double[iMax(strengthSize, 0)];
		
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
				if (d.q - prevDip.q < P_DIP_MERGE_LIM*2*halfTu) {
					prevDip = new DipByStrength((d.q + prevDip.q)/2, dMax(d.strength, prevDip.strength));
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
			else if (d.strength < P_DIP_STRENGTH_MIN) {
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
		for (Dip d : dipsByTime) {
			if (count == 0) {
				prevQ = d.q;
			}
			else if (d.q - prevQ > P_DIP_DASHMIN*halfTu) {
				p = p.newNode("-");
			}
			else {
				p = p.newNode(".");
			}
			prevQ = d.q;
			count++;
		}

		new Debug("char no: %d, decoded: %s", charNo, p.text);
		formatter.add(false, p.text, -1);
		tc.chCus += p.nTus;
	}
	
	private static void wpmReport(
			int chCus,
			int chTicks,
			int spCus,
			int spTicks,
			double tuMillis,
			double tsLength) {
	
		if (spCus > 0) {
			new Info("expansion of inter-char and inter-word spaces: %.3f",
					(spTicks*tsLength)/spCus);
		}
		if (chTicks > 0) {
			new Info("within-characters WPM rating: %.1f",
					1200*chCus/(chTicks*tuMillis*tsLength));
		}
		
		if (spTicks + chTicks > 0) {
			new Info("effective WPM: %.1f",
					1200*(spCus + chCus)/((spTicks + chTicks)*tuMillis*tsLength));
		}
	}

	private static double threshold(double effectiveLevel, double floor, double ceiling) {
		return floor + effectiveLevel*(ceiling - floor);
	}

	private static void phasePlot(int fBest, int nofSlices, int framesPerSlice, Wav w,
			int clipLevel, double[] sig, double level,
			double[] flo, double[] cei,
			int decoder) throws IOException, InterruptedException {
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
								iMax(-clipLevel, ampRaw) :
									iMin(clipLevel, ampRaw);

								final double angle = angleOffset + TWO_PI*fBest*j/w.frameRate;
								sinAcc += Math.sin(angle)*amp;
								cosAcc += Math.cos(angle)*amp;
					}
					cosSum[q] = cosAcc;
					sinSum[q] = sinAcc;
		}

		for (int q = 0; q < wphi.length; q++) {
			wphi[q] = wphi(q, cosSum, sinSum, sig, level, flo, cei, decoder);
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
						dMin(t2 + w.length, t1);
			if (w.length != -1 && t2 + w.length > t1) {
				new Warning("offset+length exceeds %.1f seconds", t1);
			}
			
			final double t4 =
					dMax(GerkeLib.getDoubleOptMulti(O_PLINT)[0], t2);
			if (t4 >= t3) {
				new Death("plot interval out of bounds");
			}
			else if (GerkeLib.getDoubleOptMulti(O_PLINT)[0] < t2) {
				new Warning("starting plot interval at: %.1f s", t2);
			}
			
			final double t5 =
					GerkeLib.getDoubleOptMulti(O_PLINT)[1] == -1.0 ?
							t3 :
								dMin(t3, t4 + GerkeLib.getDoubleOptMulti(O_PLINT)[1]);
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

	private static double squared(double x) {
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
			double[] flo,
			double[] cei,
			int decoder) {
		
		final int len = sig.length;
		final int width = 7;
		
		double sumx = 0.0;
		double sumy = 0.0;
		double ampAve = 0.0;
		int m = 0;
		for (int j = iMax(0, k-width); j <= iMin(len-1, k+width); j++) {
			final double amp = sig[j];
			ampAve += amp;
			m++;
			sumx += amp*amp*x[j];
			sumy += amp*amp*y[j];
		}
		ampAve = ampAve/m;
		
		if (ampAve < threshold(0.2*level*THRESHOLD[decoder], flo[k], cei[k])) {
			return 0.0;
		}
		else {
			return Math.atan2(sumy, sumx);
		}
	}

	/**
	 * Estimate best frequency. Optionally produce a plot.
	 * 
	 * @param frameRate
	 * @param framesPerSlice
	 * @return
	 * @throws IOException
	 * @throws InterruptedException
	 */
	private static int findFrequency(int framesPerSlice, Wav w) throws IOException, InterruptedException {
		
		final int nofSubOptions = GerkeLib.getOptMultiLength(O_FRANGE);
		if (nofSubOptions != 2) {
			new Death("expecting 2 suboptions, got: %d", nofSubOptions);
			
		}
		final int f0 = GerkeLib.getIntOptMulti(O_FRANGE)[0];
		final int f1 = GerkeLib.getIntOptMulti(O_FRANGE)[1];
		new Debug("search for frequency in range: %d to %d", f0, f1);

		final SortedMap<Integer, Double> pairs =
				GerkeLib.getFlag(O_FPLOT) ? new TreeMap<Integer, Double>() : null;
		
		// search in steps of 10 Hz, PARAMETER
		final int fStepCoarse = 10;
		
		int fBest = -1;
		double rSquaredSumBest = -1.0;
		for (int f = f0; f <= f1; f += fStepCoarse) {
			final double rSquaredSum = r2Sum(f, framesPerSlice, w);
			if (pairs != null) {
				//fPlot.ps.println(String.format("%d %f", f, rSquaredSum));
				pairs.put(new Integer(f), rSquaredSum);
			}
			if (rSquaredSum > rSquaredSumBest) {
				rSquaredSumBest = rSquaredSum;
				fBest = f;
			}
		}
		
		// refine, steps of 1 Hz, PARAMETER
		final int fStepFine = 1;
		final int g0 = iMax(0, fBest - 18*fStepFine);
		final int g1 = fBest + 18*fStepFine;
		for (int f = g0; f <= g1; f += fStepFine) {
			final double rSquaredSum = r2Sum(f, framesPerSlice, w);
			if (pairs != null) {
				// fPlot.ps.println(String.format("%d %f", f, rSquaredSum));
				pairs.put(new Integer(f), rSquaredSum);
			}
			if (rSquaredSum > rSquaredSumBest) {
				rSquaredSumBest = rSquaredSum;
				fBest = f;
			}
		}
		
		if (fBest == g0 || fBest == g1) {
			new Warning("frequency may not be optimal, try a wider range");
		}
		
		if (pairs != null) {
			final PlotCollector fPlot = new PlotCollector();
			for(Entry<Integer, Double> e : pairs.entrySet()) {
				fPlot.ps.println(String.format("%d %f", e.getKey().intValue(), e.getValue()));
			}
			fPlot.plot(new Mode[] {Mode.POINTS}, 1);
		}
		
		return fBest;
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

		int q1 = iMax(q-width/2, 0);
		int q2 = iMin(q+width/2, sig.length);
		
		// find the maximal sig value
		double sigMax = -1.0;
		for (int j = q1; j < q2; j++) {
			sigMax = dMax(sig[j], sigMax);
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
			final int decr = iMin(hist[k], count);
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

		int q1 = iMax(q-width/2, 0);
		int q2 = iMin(q+width/2, sig.length);
		
		// find the maximum amplitude
		double sigMax = -1.0;
		for (int j = q1; j < q2; j++) {
			sigMax = dMax(sig[j], sigMax);
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
			final int decr = iMin(hist[k], count);
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

	
	private static double r2Sum(int f, int framesPerSlice, Wav w) {
		
		double rSquaredSum = 0.0;
		final TrigTable trigTable = new TrigTable(f, framesPerSlice, w.frameRate);
		for (int q = 0; true; q++) {
			if (w.wav.length - q*framesPerSlice < framesPerSlice) {
				return rSquaredSum;
			}
			double sinAcc = 0.0;
			double cosAcc = 0.0;
			for (int j = 0; j < framesPerSlice; j++) {
				final short amp = w.wav[q*framesPerSlice + j];
				sinAcc += trigTable.sin(j)*amp;
				cosAcc += trigTable.cos(j)*amp;
			}
			final double rSquared = sinAcc*sinAcc + cosAcc*cosAcc;
			rSquaredSum += rSquared;
		}
	}

	/**
	 * Iteratively determine a clipping level.
	 * 
	 * @param f
	 * @param framesPerSlice
	 * @param w
	 * @return
	 */
	private static int getClipLevel(int f, int framesPerSlice, Wav w) {

		final double delta = P_CLIP_PREC*(1.0 - P_CLIP_STRENGTH);
		
		final double uNoClip = signalAverage(f, framesPerSlice, Short.MAX_VALUE, w);
		new Debug("clip level: %d, signal: %f", Short.MAX_VALUE, uNoClip);

		int hi = Short.MAX_VALUE;
		int lo = 0;
		for (; true;) {
			
			final int midpoint = (hi + lo)/2;
			if (midpoint == lo) {
				// cannot improve more
				return hi;
			}

			double uNew = signalAverage(f, framesPerSlice, midpoint, w);
			new Trace("clip level: %d, signal: %f", midpoint, uNew);
			
			if ((1 - P_CLIP_STRENGTH)*uNoClip > uNew && uNew > (1 - P_CLIP_STRENGTH - delta)*uNoClip) {
				new Debug("using clip level: %d", midpoint);
				return midpoint;
			}
			else if (uNew >= (1 - P_CLIP_STRENGTH)*uNoClip) {
				// need to clip more
				hi = midpoint;
			}
			else {
				// need to clip less
				lo = midpoint;
			}
		}
	}

	/**
	 * Average signal over entire capture, at frequency f. The time slice
	 * length is (framesPerSlice/frameRate)*1000 (ms). Clipping is applied.
	 * 
	 * @param f
	 * @param frameRate
	 * @param framesPerSlice
	 * @return
	 */
	private static double signalAverage(int f, int framesPerSlice, int clipLevel, Wav w) {

		final TrigTable trigTable = new TrigTable(f, framesPerSlice, w.frameRate);

		double rSum = 0.0;
		int divisor = 0;
		for (int q = 0; true; q++) {
			if (w.wav.length - q*framesPerSlice < framesPerSlice) {
				break;
			}
			
			double sinAcc = 0.0;
			double cosAcc = 0.0;

			for (int j = 0; j < framesPerSlice; j++) {
				final int ampRaw = (int) w.wav[q*framesPerSlice + j];
				final int amp = ampRaw < 0 ? iMax(ampRaw, -clipLevel) : iMin(ampRaw, clipLevel);
				sinAcc += trigTable.sin(j)*amp;
				cosAcc += trigTable.cos(j)*amp;
			}

			// this quantity is proportional to signal amplitude in time slice
			final double r = Math.sqrt(sinAcc*sinAcc + cosAcc*cosAcc)/framesPerSlice;
			
			rSum += framesPerSlice*r;
			divisor += framesPerSlice;
		}

		return rSum/divisor;
	}
	
	private static double dMax(double a, double b) {
		return a > b ? a : b;
	}
	
	private static double dMin(double a, double b) {
		return a < b ? a : b;
	}
	
	private static int iMax(int a, int b) {
		return a > b ? a : b;
	}
	
	private static int iMin(int a, int b) {
		return a < b ? a : b;
	}
	
	private static int roundToOdd(double x) {
		final int k = (int) Math.round(x);
		return k % 2 == 0 ? k+1 : k;
	}
	
	private static int ensureEven(int k) {
		return k % 2 == 1 ? k+1 : k;
	}
}
