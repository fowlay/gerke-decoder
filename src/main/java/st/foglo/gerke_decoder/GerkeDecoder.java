package st.foglo.gerke_decoder;

// gerke-decoder - translates Morse code audio to text
//
// Copyright (C) 2020 Rabbe Fogelholm
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
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;

import st.foglo.gerke_decoder.GerkeDecoder.PlotCollector.Mode;
import st.foglo.gerke_decoder.GerkeLib.*;

public final class GerkeDecoder {
	
	static final int HI = 10;
	static final int LO = -15;
	
	static final double TWO_PI = 2*Math.PI;
	
	static final double THRESHOLD = 0.45;
	
	/**
	 * The tone amplitude is computed by taking signal values in time slices
	 * that span a time interval that is 2 * NOF_TU_FOR_HISTOGRAM * TU
	 * and building a histogram, discarding the low signal values, and
	 * taking the average of remaining values.
	 */
	static final int NOF_TU_FOR_CEILING_HISTOGRAM = 30; // 30;
	static final int NOF_TU_FOR_FLOOR_HISTOGRAM = 30; // 30;
	
	static final int HIST_SIZE_CEILING = 100;
	static final int HIST_SIZE_FLOOR = 100;

	static final String O_VERSION = "version";
	static final String O_OFFSET = "offset";
	static final String O_LENGTH = "length";
	static final String O_FRANGE = "frange";
	static final String O_FREQ = "freq";
	static final String O_WPM = "wpm";
	static final String O_CLIPPING = "clip";
	static final String O_STIME = "sample-time";
	static final String O_SIGMA = "sigma";
	static final String O_LEVEL = "level";
	static final String O_TSTAMPS = "timestamps";
	static final String O_FPLOT = "frequency-plot";
	static final String O_PLINT = "plot-interval";
	static final String O_PLOT = "plot";
	static final String O_PPLOT = "phase-plot";
	static final String O_VERBOSE = "verbose";
	
	static final String O_HIDDEN = "hidden-options";
	enum HiddenOpts {DIP, SPIKE, BREAK_LONG_DASH, CUTOFF, FILTER, ORDER, PMLEVEL};
	
	static final double WORD_SPACE_LIMIT = 5.3;    // words break <---------+---------> words stick
	static final double CHAR_SPACE_LIMIT = 1.75;   // chars break <---------+---------> chars cluster
	static final double DASH_LIMIT = 1.7;          // 1.6
	static final double TWO_DASH_LIMIT = 6.0;      // 6.0

	static {
		new VersionOption("V", O_VERSION, "gerke-decoder version 2.0 preliminary 2");

		new SingleValueOption("o", O_OFFSET, "0");
		new SingleValueOption("l", O_LENGTH, "-1");

		new SingleValueOption("F", O_FRANGE, "400,1200");
		new SingleValueOption("f", O_FREQ, "-1");
		
		new SingleValueOption("w", "wpm", "15.0");

		new SingleValueOption("c", O_CLIPPING, "-1");
		new SingleValueOption("q", O_STIME, "0.10");
		new SingleValueOption("s", O_SIGMA, "0.33");

		new SingleValueOption("D", "dip-spike", "0.002,0.005");

		new SingleValueOption("u", O_LEVEL, "1.0");
		new Flag("t", O_TSTAMPS);

		new Flag("S", O_FPLOT);
		
		new SingleValueOption("Z", O_PLINT, "0,-1");
		new Flag("P", O_PLOT);
		new Flag("Q", O_PPLOT);

		new SteppingOption("v", O_VERBOSE);

		new SingleValueOption("H", O_HIDDEN,
				        "0.002"+                    // dip removal
		                ",0.005"+                   // spike removal
				        ",1"+                       // break too long dashes
						",1.2"+                     // frequency, relative to 1/TU
						",b"+                       // b: butterworth, cI: chebyshev I, w: sliding window, n: no filter
						",2"+                       // filter order
						",1.0"                      // level in character decoder
				);

		new HelpOption(
				"h",
new String[]{
		"Usage is: java -jar gerke-decoder.jar [OPTIONS] WAVFILE",
		"Options are:",
		String.format("  -o OFFSET          Offset (seconds)"),
		String.format("  -l LENGTH          Length (seconds)"),
		
		String.format("  -w WPM             WPM, tentative, defaults to %s", GerkeLib.getDefault(O_WPM)),
		String.format("  -F LOW,HIGH        Audio frequency search range, defaults to %s", GerkeLib.getDefault(O_FRANGE)),
		String.format("  -f FREQ            Audio frequency, bypassing search"),
		String.format("  -c CLIPLEVEL       Clipping level, optional"),
		String.format("  -u ADJUSTMENT      Threshold adjustment, defaults to %s", GerkeLib.getDefault(O_LEVEL)),

		String.format("  -q SAMPLE_PERIOD   sample period, defaults to %s TU", GerkeLib.getDefault(O_STIME)),
		String.format("  -s SIGMA           Gaussian sigma, defaults to %s TU", GerkeLib.getDefault(O_SIGMA)),

		String.format("  -H PARAMETERS      Experimental parameters, default: %s", GerkeLib.getDefault(O_HIDDEN)),
		
		String.format("  -S                 Generate frequency spectrum plot"),
		String.format("  -P                 Generate signal plot"),
		String.format("  -Q                 Generate phase angle plot"),

		String.format("  -Z START,LENGTH    Time interval for signal and phase plot (seconds)"),
		String.format("  -t                 Insert timestamps in decoded text"),
		String.format("  -v                 Verbosity (may be given several times)"),
		String.format("  -V                 Show version"),
		String.format("  -h                 This help"),
		"",
		"A tentative TU length (length of one dot) is derived from the WPM value",
		"The TU length in ms is taken as = 1200/WPM.",
		"",
		"The SAMPLE_PERIOD parameter defines the periodicity of signal evaluation",
		"given in TU units.",
		"",
		"The SIGMA parameter defines the width, given in TU units, of the Gaussian",
		"used in computing the signal value."
				});
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
			this.text = text != null ? text : "["+code+"]";
			this.nTus = tuCount;
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

	static short[] wav;
	
	static class FilterRunner implements Runnable {

		final LowpassFilter f;
		final short[] wav;
		final double[] out;

		final int framesPerSlice;
		final int clipLevel;

		final int freq;
		final int frameRate;
		final double phaseShift;

		final CountDownLatch cdl;

		public FilterRunner(LowpassFilter f, short[] wav, double[] out,
				int framesPerSlice,
				int clipLevel,
				int freq,
				int frameRate,
				double phaseShift,
				CountDownLatch cdl) {
			this.f = f;
			this.wav = wav;
			this.out = out;

			this.framesPerSlice = framesPerSlice;
			this.clipLevel = clipLevel;

			this.freq = freq;
			this.frameRate = frameRate;

			this.phaseShift = phaseShift;

			this.cdl = cdl;
		}

		@Override
		public void run() {
			for (int q = 0; true; q++) {      //  q is out[] index

				if (wav.length - q*framesPerSlice < framesPerSlice) {
					break;
				}

				// feed the filters with wav samples
				for (int k = -framesPerSlice+1; k <= 0; k++) {

					int wavIndex = q*framesPerSlice + k;

					double outSignal = 0.0;
					if (wavIndex >= 0) {
						int ampRaw = wav[wavIndex];   // k is non-positive!
						final int amp = ampRaw < 0 ? iMax(-clipLevel, ampRaw) : iMin(clipLevel, ampRaw);

						double angle = ((freq*wavIndex)*TWO_PI)/frameRate;
						outSignal = f.filter(amp*Math.sin(angle+phaseShift));
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
		
		String fileName;
		String fileNameWin;
		PrintStream ps;
		
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
	
	static abstract class PlotEntryBase{
	}
	
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
	
	
	static final class PlotEntryDecode extends PlotEntryBase {
		final int dec;

		public PlotEntryDecode(int dec) {
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
			
			int index = 0;
			for (char x : code.toCharArray()) {
				if (index > 0) {
					pattern[index] = LO;
					index++;
				}
				if (x == '.') {
					pattern[index] = HI;
					index++;
				}
				else if (x == '-') {
					pattern[index] = HI;
					pattern[index+1] = HI;
					pattern[index+2] = HI;
					index += 3;
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
	 * Maybe only head and last list element are ever used? TODO
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

	public static void main(String[] clArgs) {
		
		// Ensure that decimal points (not commas) are used
		Locale.setDefault(new Locale("en", "US"));
		
		try {
			GerkeLib.parseArgs(clArgs);
			showClData();

			if (GerkeLib.nofArguments() != 1) {
				new Death("expecting one filename argument, try -h for help");
			}

			final String file = GerkeLib.getArgument(0);

			final AudioInputStream ais = AudioSystem.getAudioInputStream(new File(file));
			
			final AudioFormat af = ais.getFormat();
			new Info("audio format: %s", af.toString());
			
			final int nch = af.getChannels();
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

			final int frameRate = Math.round(af.getFrameRate());
			new Info("frame rate: %d", frameRate);
			
			final long frameLength = ais.getFrameLength();
			new Info(".wav file length: %.1f s", (double)frameLength/frameRate);
			new Info("nof. frames: %d", frameLength);

			// Get the tentative WPM
			final double wpm = GerkeLib.getDoubleOpt(O_WPM);
			final double tuMillis = 1200/wpm;
			new Info("dot time, tentative: %.3f ms", tuMillis);

			// tsLength is the relative TS length.
			// 0.10 is a typical value.
			// TS length in ms is: tsLength*tuMillis
			
			final double tsLengthGiven = GerkeLib.getDoubleOpt(O_STIME);
			
			final int framesPerSlice = (int) Math.round(tsLengthGiven*frameRate*tuMillis/1000.0);
			
			final double tsLength = 1000.0*framesPerSlice/(frameRate*tuMillis);
			
			
			new Info("time slice: %.3f ms", 1000.0*framesPerSlice/frameRate);
			new Info("frames per time slice: %d", framesPerSlice);
			
			new Debug("time slice roundoff: %e", (tsLength - tsLengthGiven)/tsLengthGiven);

			// Set number of frames to use from offset and length given in seconds
			final int length = GerkeLib.getIntOpt(O_LENGTH);
			
			if (GerkeLib.getIntOpt(O_OFFSET) < 0) {
				new Death("offset cannot be negative");
			}
			final int offsetFrames = GerkeLib.getIntOpt(O_OFFSET)*frameRate;
			final int nofFrames = length == -1 ? ((int) (frameLength - offsetFrames)) : length*frameRate;

			final int nofSlices = nofFrames/framesPerSlice;

			if (nofFrames < 0) {
				new Death("offset too large, WAV file length is: %f s", (double)frameLength/frameRate);
			}
			
			final double plotBegin;
			final double plotEnd;
			if (GerkeLib.getOptMultiLength(O_PLINT) != 2) {
				new Death("bad plot interval: wrong number of suboptions");
			}
			
			final boolean phasePlot = GerkeLib.getFlag(O_PPLOT);
			if (GerkeLib.getFlag(O_PLOT) || phasePlot) {
				
				final double t1 = ((double) frameLength)/frameRate;
				
				final double t2 = (double) (GerkeLib.getIntOpt(O_OFFSET));
				
				final double t3 = 
						length == -1 ? t1 :
							dMin(t2 + length, t1);
				if (length != -1 && t2 + length > t1) {
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

				plotBegin = t4;
				plotEnd = t5;
				if (plotBegin >= plotEnd) {
					new Death("bad plot interval");
				}
			}
			else {
				plotBegin = 0.0;
				plotEnd = 0.0;
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

			// Estimate audible signal frequency
			final int fBest;
			if (GerkeLib.getIntOpt(O_FREQ) != -1) {
				// frequency is specified on the command line
				if (GerkeLib.getFlag(O_FPLOT)) {
					new Warning("frequency plot skipped when -f option given");
				}
				fBest = GerkeLib.getIntOpt(O_FREQ);
				new Info("specified frequency: %d", fBest);
			}
			else {
				// no frequency specified, search for best value
				fBest = findFrequency(frameRate, framesPerSlice);
				new Info("estimated frequency: %d", fBest);
			}

			// ===========================================================================
			// using the frequency, and a time slice, and a frame time,
			// find the average rSquared for use as a threshold value

			final int clipLevelOverride = GerkeLib.getIntOpt(O_CLIPPING);
			
			final int clipLevel =
					clipLevelOverride != -1 ? clipLevelOverride : getClipLevel(fBest, frameRate, framesPerSlice);
			new Info("clipping level: %d", clipLevel);

			// ===========================================================================
			// decode the stream

			// The limits are expressed as "number of time slice".
			

			
			final long dashLimit = Math.round(DASH_LIMIT*tuMillis*frameRate/(1000*framesPerSlice));        // PARAMETER
			final long wordSpaceLimit = Math.round(WORD_SPACE_LIMIT*tuMillis*frameRate/(1000*framesPerSlice));   // PARAMETER
			final long charSpaceLimit = Math.round(CHAR_SPACE_LIMIT*tuMillis*frameRate/(1000*framesPerSlice));   // PARAMETER
			final long twoDashLimit = Math.round(TWO_DASH_LIMIT*tuMillis*frameRate/(1000*framesPerSlice));     // PARAMETER
			
			final double level = GerkeLib.getDoubleOpt(O_LEVEL);
			new Info("relative tone/silence threshold: %.3f", level);

			new Debug("dash limit: %d, word space limit: %d", dashLimit, wordSpaceLimit);

			
			final double saRaw = signalAverage(fBest, frameRate, framesPerSlice, Short.MAX_VALUE);
			new Debug("signal average: %f", saRaw);
			final double sa = signalAverage(fBest, frameRate, framesPerSlice, clipLevel);
			new Debug("signal average clipped: %f", sa);
			
			final int histWidth = (int)(NOF_TU_FOR_CEILING_HISTOGRAM*tuMillis/(1000.0*framesPerSlice/frameRate));
			new Debug("histogram half-width (nof. slices): %d", histWidth);

			

			final double[] sig = new double[nofSlices];

			final LowpassFilter filterI;
			final LowpassFilter filterQ;
			if (GerkeLib.getOptMulti(O_HIDDEN)[HiddenOpts.FILTER.ordinal()].equals("b")) {
				final int order = GerkeLib.getIntOptMulti(O_HIDDEN)[HiddenOpts.ORDER.ordinal()];
				double cutoff =
						GerkeLib.getDoubleOptMulti(O_HIDDEN)[HiddenOpts.CUTOFF.ordinal()];
				filterI = new LowpassButterworth(order, (double)frameRate, cutoff* 1000.0/tuMillis, 0.0);
				filterQ = new LowpassButterworth(order, (double)frameRate, cutoff* 1000.0/tuMillis, 0.0);
			}
			else if (GerkeLib.getOptMulti(O_HIDDEN)[HiddenOpts.FILTER.ordinal()].equals("cI")) {
				final int order = GerkeLib.getIntOptMulti(O_HIDDEN)[HiddenOpts.ORDER.ordinal()];
				double cutoff =
						GerkeLib.getDoubleOptMulti(O_HIDDEN)[HiddenOpts.CUTOFF.ordinal()];
				// PARAMETER 2.0 dB, ripple
				filterI = new LowPassChebyshevI(order, (double)frameRate, cutoff* 1000.0/tuMillis, 1.5);
				filterQ = new LowPassChebyshevI(order, (double)frameRate, cutoff* 1000.0/tuMillis, 1.5);
			}
			else if (GerkeLib.getOptMulti(O_HIDDEN)[HiddenOpts.FILTER.ordinal()].equals("w")) {
				double cutoff =
						GerkeLib.getDoubleOptMulti(O_HIDDEN)[HiddenOpts.CUTOFF.ordinal()];
				filterI = new LowpassWindow(frameRate, cutoff* 1000.0/tuMillis);
				filterQ = new LowpassWindow(frameRate, cutoff* 1000.0/tuMillis);
			}
			else if (GerkeLib.getOptMulti(O_HIDDEN)[HiddenOpts.FILTER.ordinal()].equals("t")) {
				filterI = new LowpassTimeSliceSum(framesPerSlice);
				filterQ = new LowpassTimeSliceSum(framesPerSlice);
			}
			else if (GerkeLib.getOptMulti(O_HIDDEN)[HiddenOpts.FILTER.ordinal()].equals("n")) {
				filterI = new LowpassNone();
				filterQ = new LowpassNone();
			}
			else {
				new Death("no such filter supported");
				throw new Exception();
			}

			final double[] outSin = new double[nofSlices];
			final double[] outCos = new double[nofSlices];
			
			final long tBegin = System.currentTimeMillis();
			final CountDownLatch cdl = new CountDownLatch(2);
			
			final Thread t1 =
					new Thread(
							new FilterRunner(
									filterI,
									wav,
									outSin,
									framesPerSlice,
									clipLevel, fBest,
									frameRate,
									0.0,
									cdl));
			t1.start();
			final Thread t2 =
					new Thread(
							new FilterRunner(
									filterQ,
									wav,
									outCos,
									framesPerSlice,
									clipLevel, fBest,
									frameRate,
									Math.PI/2,
									cdl));
			t2.start();
			cdl.await();
			
			new Info("filtering took ms: %d", System.currentTimeMillis() - tBegin);

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

				if (wav.length - q*framesPerSlice < framesPerSlice) {
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

				
				
				
//			
//			    // feed the filters with wav samples
//			    for (int k = -framesPerSlice+1; k <= 0; k++) {
//			    	
//			    	int wavIndex = q*framesPerSlice + k;
//			    	
//			    	double outSin = 0.0;
//		    		double outCos = 0.0;
//			    	if (wavIndex >= 0) {
//			    		int ampRaw = wav[wavIndex];   // k is non-positive!
//			    		final int amp = ampRaw < 0 ? iMax(-clipLevel, ampRaw) : iMin(clipLevel, ampRaw);
//
//			    		double angle = TWO_PI*fBest*wavIndex/frameRate;
//			    		outSin = filterSin.filter(amp*Math.sin(angle));
//			    		outCos = filterCos.filter(amp*Math.cos(angle));
//			    	}
//			    	
//			    	if (k == 0) {
//			    		int ringIndex = q % gaussSize;
//			    		ringBuffer[ringIndex] = Math.sqrt(outSin*outSin + outCos*outCos);
//			    		int rr = ringIndex;
//			    		double ss = 0.0;
//			    		for (int ii = 0; ii < gaussSize; ii++) {
//			    			ss += expTable[ii]*ringBuffer[rr];
//			    			rr = rr+1 == gaussSize ? 0 : rr+1;
//			    		}
//			    		sig[q] = ss/gaussSize;
//			    		filterSin.reset();
//			    		filterCos.reset();
//			    	}
//			    }
//			}

			if (phasePlot) {
				final double[] cosSum = new double[nofSlices];
				final double[] sinSum = new double[nofSlices];
				final double[] wphi = new double[nofSlices];

				for (int q = 0; true; q++) {

					if (wav.length - q*framesPerSlice < framesPerSlice) {
						break;
					}

					final double angleOffset =
							phasePlot ? TWO_PI*fBest*timeSeconds(q, framesPerSlice, frameRate, offsetFrames) : 0.0;

							double sinAcc = 0.0;
							double cosAcc = 0.0;
							for (int j = 0; j < framesPerSlice; j++) {
								// j is frame index
								final int ampRaw = wav[q*framesPerSlice + j];
								final int amp = ampRaw < 0 ?
										iMax(-clipLevel, ampRaw) :
											iMin(clipLevel, ampRaw);

										final double angle = angleOffset + TWO_PI*fBest*j/frameRate;
										sinAcc += Math.sin(angle)*amp;
										cosAcc += Math.cos(angle)*amp;
							}
							cosSum[q] = cosAcc;
							sinSum[q] = sinAcc;
				}

				for (int q = 0; q < wphi.length; q++) {
					wphi[q] = wphi(q, cosSum, sinSum, sig, level, histWidth);
				}

				PlotCollector pcPhase = new PlotCollector();
				for (int q = 0; q < wphi.length; q++) {
					final double seconds = timeSeconds(q, framesPerSlice, frameRate, offsetFrames);
					if (plotBegin <= seconds && seconds <= plotEnd) {
						final double phase = wphi[q];
						if (phase != 0.0) {
							pcPhase.ps.println(String.format("%f %f", seconds, phase));
						}
					}
				}
				pcPhase.plot(new Mode[] {Mode.POINTS}, 1);
			}

			final PlotCollector pcSignal;
			final SortedMap<Double, List<PlotEntryBase>> plotEntries;
			
			if (GerkeLib.getFlag(O_PLOT)) {
				pcSignal = new PlotCollector();
				plotEntries = new TreeMap<Double, List<PlotEntryBase>>();
			}
			else {
				pcSignal = null;
				plotEntries = null;
			}



			final Trans[] trans = new Trans[nofSlices];
			int transIndex = 0;
			boolean tone = false;
			double dipAcc = 0.0;
			double spikeAcc = 0.0;
			double thresholdMax = -1.0;
			double ceilingMax = -1.0;
			new Debug("thresholdMax is: %e", thresholdMax);


			for (int q = 0; true; q++) {
				if (wav.length - q*framesPerSlice < framesPerSlice) {
					// assume end of stream, drop the very final time slice
					break;
				}

				final double sigAverage = sig[q];
	
				final double floor = localFloorByHist(q, sig, histWidth);
				final double ceiling = localAmpByHist(q, sig, histWidth);
				final double threshold = floor + level*THRESHOLD*(ceiling - floor);
		
				thresholdMax = dMax(threshold, thresholdMax);
				ceilingMax = dMax(ceiling, ceilingMax);
				
				// new Debug("thresholdMax is (2): %e", thresholdMax);
				final boolean newTone = sigAverage > threshold;
				
				if (pcSignal != null) {
					final double seconds = timeSeconds(q, framesPerSlice, frameRate, offsetFrames);
					if (plotBegin <= seconds && seconds <= plotEnd) {
						final List<PlotEntryBase> list = new ArrayList<PlotEntryBase>(2);
						list.add(new PlotEntrySig(sigAverage, threshold, ceiling, floor));
						plotEntries.put(new Double(seconds), list);
					}
				}
				
				if (newTone && !tone) {
					// raise
					if (transIndex > 0 && q - trans[transIndex-1].q <= charSpaceLimit) {
						trans[transIndex] = new Trans(q, true, dipAcc, ceiling, floor);
					}
					else {
						trans[transIndex] = new Trans(q, true, ceiling, floor);
					}
					transIndex++;
					tone = true;
					spikeAcc = squared(threshold - sigAverage);
				}
				else if (!newTone && tone) {
					// fall
					trans[transIndex] = new Trans(q, false, spikeAcc, ceiling, floor);
					transIndex++;
					tone = false;
					dipAcc = squared(threshold - sigAverage);
				}
				else if (!tone) {
					dipAcc += squared(threshold - sigAverage);
				}
				else if (tone) {
					spikeAcc += squared(threshold - sigAverage);
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
				// TODO, refactor
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

			if (GerkeLib.getFlag(O_PLOT)) {
				// scan the trans[] array, create Plot entries
				int initDigitizedSignal = 0;
				for (int t = 0; t < transIndex; t++) {
					final double sec0 = timeSeconds(trans[t].q, framesPerSlice, frameRate, offsetFrames);
					
					if (plotBegin <= sec0 && sec0 <= plotEnd) {
						
						if (initDigitizedSignal == 0) {
							initDigitizedSignal = trans[t].rise ? 1 : 2;
						}
						
						final List<PlotEntryBase> list = plotEntries.get(new Double(sec0));
						
						if (list == null) {
							// not expected to happen
							final List<PlotEntryBase> newList = new ArrayList<PlotEntryBase>(2);
							newList.add(new PlotEntryDecode(trans[t].rise ? 2 : 1));
							plotEntries.put(new Double(sec0), newList);
						}
						else {
							list.add(
									new PlotEntryDecode(
											(int) Math.round(
													(trans[t].rise ? 2 : 1) * ceilingMax/20)));
						}
					}
				}

				// at this point the plot stream can be created
				double signa = 0.0;
				double thresha = 0.0;
				double ceiling = 0.0;
				double floor = 0.0;
				int digitizedSignal = initDigitizedSignal;
				for (Entry<Double, List<PlotEntryBase>> e : plotEntries.entrySet()) {

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

					pcSignal.ps.println(String.format("%f %f %f %f %f %d",
							e.getKey().doubleValue(), signa, thresha, ceiling, floor, digitizedSignal));
				}
			}

			
			if (transIndex == 0) {
				new Death("no signal detected");
			}
			else if (transIndex == 1) {
				new Death("no code detected");
			}

			final Formatter formatter = new Formatter();
			
			boolean prevTone = false;
			int qBeginSilence = 0;
			
			int nTuTotal = 0;
			
			// measure inter-character spacing, ignore inter-word spacing
			int nTuSpace = 0;
			int qSpace = 0;
			
			Node p = tree;
			
			final List<CharData> cdList = new ArrayList<CharData>();
			CharData charCurrent = null;
			
			final int offset = GerkeLib.getIntOpt(O_OFFSET);
			for (int t = 0; t < transIndex; t++) {

				final boolean newTone = trans[t].rise;

				if (!prevTone && newTone) {
					// silent -> tone
					if (t == 0) {
						p = tree;
						charCurrent = new CharData(trans[t]);
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
						nTuTotal += p.nTus + 7;
						p = tree;
						cdList.add(charCurrent);
						cdList.add(new CharData());                   // empty CharData represents word space
						charCurrent = new CharData(trans[t]);
					}
					else if (trans[t].q - trans[t-1].q > charSpaceLimit) {
						formatter.add(false, p.text, -1);
						nTuTotal += p.nTus + 3;
						nTuSpace += 3;
						qSpace += (trans[t].q - qBeginSilence);
						p = tree;
						cdList.add(charCurrent);
						charCurrent = new CharData(trans[t]);
					}
					else {
						charCurrent.add(trans[t]);
					}
				}
				else if (prevTone && !newTone) {
					// tone -> silent
					qBeginSilence = trans[t].q;
					final int dashSize = trans[t].q - trans[t-1].q;
					if (dashSize > dashLimit) {
						if (p.dash == null) {
							final String newCode = p.code + "-";
							p.dash = new Node(null, newCode);
						}
						p = p.dash;
					}
					else {
						if (p.dot == null) {
							final String newCode = p.code + ".";
							p.dot = new Node(null, newCode);
						}
						p = p.dot;
					}
					charCurrent.add(trans[t]);
				}
				else {
					new Death("internal error");
				}
				
				prevTone = newTone;
			}
			
			if (p != tree) {
				formatter.add(true, p.text, -1);
				nTuTotal += p.nTus;
				formatter.newLine();
			}
			else if (p == tree && formatter.getPos() > 0) {
				formatter.flush();
				formatter.newLine();
			}
			
			
			if (!charCurrent.isEmpty()) {
				if (charCurrent.isComplete()) {
					cdList.add(charCurrent);
				}
			}
			
			new Info("decoded text MD5 digest: %s\n", formatter.getDigest());			
			
			
			
			decoder(cdList, sig, tsLength, offset, tuMillis);
			
			


			if (pcSignal != null) {
				pcSignal.plot(new Mode[] {Mode.LINES_PURPLE, Mode.LINES_RED, Mode.LINES_GREEN, Mode.LINES_GREEN, Mode.LINES_CYAN}, 5);
			}
			
			final double tuEffMillisActual =
					(qBeginSilence - trans[0].q)*tsLength*tuMillis/nTuTotal;

			new Info("effective WPM: %.1f", 1200.0/tuEffMillisActual);
			new Info("inter-character spaces extension factor: %.3f",
					(qSpace*tsLength/nTuSpace)*(tuMillis/tuEffMillisActual));

		}
		catch (Exception e) {
			new Death(e);
		}
	}

	private static void decoder(List<CharData> cdList, double[] sig, double tsLength, int offset, double tuMillis) throws Exception {
		
		final Formatter ff = new Formatter();

		int ts = -1;
		
		for (CharData cd : cdList) {

			if (cd.isEmpty()) {
				ff.add(true, "", ts);
			}
			else {
				
				ts = GerkeLib.getFlag(O_TSTAMPS) ? 
						offset + (int) Math.round(cd.transes.get(0).q*tsLength*tuMillis/1000) : -1;
				
				String text = decodeChar(cd, sig, tsLength, offset, tuMillis);
				ff.add(false, text, -1);
			}
		}
		ff.flush();
		if (ff.getPos() > 0) {
			ff.newLine();
		}
		
		new Info("decoded text MD5 digest: %s", ff.getDigest());
		
	}

	private static String decodeChar(CharData cd, double[] sig, double tsLength, int offset, double tuMillis) {
		final int qSize = cd.lastAdded.q - cd.transes.get(0).q;
		final int tuClass = (int) Math.round(tsLength*qSize);
		
		final double level = GerkeLib.getDoubleOptMulti(O_HIDDEN)[HiddenOpts.PMLEVEL.ordinal()];

		CharTemplates[] candsArray = 
				tuClass == 0 ? new CharTemplates[] {templs.get(new Integer(tuClass+1))} :
					tuClass == 1 ? new CharTemplates[] {templs.get(new Integer(tuClass))} :
						tuClass % 2 == 0 ? new CharTemplates[] {templs.get(new Integer(tuClass-1)), templs.get(new Integer(tuClass+1))} :
							new CharTemplates[] {templs.get(new Integer(tuClass)), templs.get(new Integer(tuClass-2))};

		CharTemplate best = null;
		double bestSum = -999999.0;
		double prio = 1.0;
		for (int j = 0; j < candsArray.length; j++) {
			if (candsArray[j] != null) {
				prio *= 0.9;

				for (CharTemplate cand : candsArray[j].list) {

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

						// PARAMETER 0.65
						sum += (sig[q] - (floor + level*0.5*(ceiling - floor)))*cand.pattern[indx];
					}
					
					sum *= prio;

					if (sum > bestSum) {
						bestSum = sum;
						best = cand;
					}
				}
			}
		}

		final String result = best == null ? "???" : best.text;

		if (GerkeLib.getIntOpt(O_VERBOSE) >= 3) {
			System.out.println(
					String.format(
							"character result: %s, time: %d, class: %d, size: %d",
							result,
							offset + (int) Math.round(cd.transes.get(0).q*tsLength*tuMillis/1000),
							tuClass,
							qSize));
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
	private static double wphi(int k, double[] x, double[] y, double[] sig, double level, int histWidth) {
		
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
		
		if (ampAve < 0.2* level*THRESHOLD*localAmpByHist(k, sig, histWidth)) {
			return 0.0;
		}
		else {
			return Math.atan2(sumy, sumx);
		}
	}

	private static int findFrequency(int frameRate, int framesPerSlice) throws IOException, InterruptedException {
		
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
		final short[] shorts = new short[framesPerSlice];
		double rSquaredSumBest = -1.0;
		for (int f = f0; f <= f1; f += fStepCoarse) {
			final double rSquaredSum = r2Sum(f, frameRate, framesPerSlice, shorts);
			if (pairs != null) {
				//fPlot.ps.println(String.format("%d %f", f, rSquaredSum));
				pairs.put(new Integer(f), rSquaredSum);
			}
			if (rSquaredSum > rSquaredSumBest) {
				rSquaredSumBest = rSquaredSum;
				fBest = f;
			}
		}
		
		// refine, steps of 1 Hz
		final int fStepFine = 1;
		final int g0 = iMax(0, fBest - 18*fStepFine);
		final int g1 = fBest + 18*fStepFine;
		for (int f = g0; f <= g1; f += fStepFine) {
			final double rSquaredSum = r2Sum(f, frameRate, framesPerSlice, shorts);
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

	private static double localAmpByHist(int q, double[] sig, int width) {

		int q1 = iMax(q-width, 0);
		int q2 = iMin(q+width, sig.length);
		
		// find the maximal sig value
		double sigMax = -1.0;
		for (int j = q1; j < q2; j++) {
			sigMax = dMax(sig[j], sigMax);
		}
		
		// produce a histogram with HIST_SIZE_CEILING slots
		int sumPoints = 0;
		final int[] hist = new int[HIST_SIZE_CEILING];
		for (int j = q1; j < q2; j++) {
			final int index = (int) Math.round(99*sig[j]/sigMax);
			int x = j < q ? q-j : j-q;
			int points = (int)Math.round(500/(1 + squared(7*x/width)));   // PARAMETER 3
			hist[index] += points;
			sumPoints += points;
		}
		
		// remove the low-amp counts
		final double remFrac = 0.70;   // hard-coded PARAMETER, larger value removes more
		int count = (int) Math.round(remFrac*sumPoints);
		int kCleared = -1;
		for (int k = 0; k < HIST_SIZE_CEILING; k++) {
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
		
		for (int k = kCleared+1; k < HIST_SIZE_CEILING; k++) {
			sumCount += hist[k];
			sumAmp += hist[k]*k*sigMax/HIST_SIZE_CEILING;
		}
		
		return sumAmp/sumCount;
	}

	private static double localFloorByHist(int q, double[] sig, int width) {

		int q1 = iMax(q-width, 0);
		int q2 = iMin(q+width, sig.length);
		
		// find the maximal sig value
		double sigMax = -1.0;
		for (int j = q1; j < q2; j++) {
			sigMax = dMax(sig[j], sigMax);
		}
		
		// produce a histogram with HIST_SIZE_FLOOR slots
		int sumPoints = 0;
		final int[] hist = new int[HIST_SIZE_FLOOR];
		for (int j = q1; j < q2; j++) {
			final int index = (int) Math.round((HIST_SIZE_FLOOR-1)*sig[j]/sigMax);
			int x = j < q ? q-j : j-q;                              // abs value
			// hard-coded parameter 4; larger value -> more localized
			int points = (int)Math.round(500/(1 + squared(5*x/width)));
			hist[index] += points;
			sumPoints += points;
		}
		
		// remove the high-signal counts
		final double remFrac = 0.60; // hard-coded PARAMETER 0.50; larger value removes more
		int kCleared = HIST_SIZE_FLOOR;
		int count = (int) Math.round(remFrac*sumPoints);
		for (int k = HIST_SIZE_FLOOR-1; k >= 0; k--) {
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
			sumAmp += hist[k]*k*sigMax/HIST_SIZE_FLOOR;
		}
		
		return sumAmp/sumCount;
	}

	
	private static double r2Sum(int f, int frameRate, int framesPerSlice, short[] shorts) {
		
		double rSquaredSum = 0.0;
		final TrigTable trigTable = new TrigTable(f, framesPerSlice, frameRate);
		for (int q = 0; true; q++) {
			if (wav.length - q*framesPerSlice < framesPerSlice) {
				return rSquaredSum;
			}
			double sinAcc = 0.0;
			double cosAcc = 0.0;
			for (int j = 0; j < framesPerSlice; j++) {
				final short amp = wav[q*framesPerSlice + j];
				sinAcc += trigTable.sin(j)*amp;
				cosAcc += trigTable.cos(j)*amp;
			}
			final double rSquared = sinAcc*sinAcc + cosAcc*cosAcc;
			rSquaredSum += rSquared;
		}
	}

	private static int getClipLevel(int f, int frameRate, int framesPerSlice) {
		
		// hardcoded PARAMETER 0.025
		final double clipStrength = 0.05;
		
		// hardcoded PARAMETER 0.005
		// smaller value means more iterations required
		final double delta = 0.005*(1.0 - clipStrength);
		
		final double uNoClip = signalAverage(f, frameRate, framesPerSlice, Short.MAX_VALUE);
		new Debug("clip level: %d, signal: %f", Short.MAX_VALUE, uNoClip);

		int hi = Short.MAX_VALUE;
		int lo = 0;
		for (; true;) {
			
			final int midpoint = (hi + lo)/2;
			if (midpoint == lo) {
				// cannot improve more
				return hi;
			}

			double uNew = signalAverage(f, frameRate, framesPerSlice, midpoint);
			new Trace("clip level: %d, signal: %f", midpoint, uNew);
			
			if ((1 - clipStrength)*uNoClip > uNew && uNew > (1 - clipStrength - delta)*uNoClip) {
				new Debug("using clip level: %d", midpoint);
				return midpoint;
			}
			else if (uNew >= (1 - clipStrength)*uNoClip) {
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
	private static double signalAverage(int f, int frameRate, int framesPerSlice, int clipLevel) {

		final TrigTable trigTable = new TrigTable(f, framesPerSlice, frameRate);

		double rSum = 0.0;
		int divisor = 0;
		for (int q = 0; true; q++) {
			if (wav.length - q*framesPerSlice < framesPerSlice) {
				break;
			}
			
			double sinAcc = 0.0;
			double cosAcc = 0.0;

			for (int j = 0; j < framesPerSlice; j++) {
				final int ampRaw = (int) wav[q*framesPerSlice + j];
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

}
