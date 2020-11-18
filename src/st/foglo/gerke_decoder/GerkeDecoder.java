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
import java.util.Map.Entry;
import java.util.SortedMap;
import java.util.TreeMap;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;

import st.foglo.gerke_decoder.GerkeDecoder.PlotCollector.Mode;
import st.foglo.gerke_decoder.GerkeLib.*;
import uk.me.berndporr.iirj.Bessel;
import uk.me.berndporr.iirj.Butterworth;
import uk.me.berndporr.iirj.ChebyshevI;
import uk.me.berndporr.iirj.ChebyshevII;

public final class GerkeDecoder {
	
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

	enum HiddenOptions {BREAK_LONG_DASH, CUTOFF, AVE_SIZE};
	
	static final double WORD_SPACE_LIMIT = 5.3;    // words break <---------+---------> words stick
	static final double CHAR_SPACE_LIMIT = 1.8;    // chars break <---------+---------> chars cluster
	static final double DASH_LIMIT = 1.7;          // 1.6
	static final double TWO_DASH_LIMIT = 6.0;      // 6.0

	static {
		new VersionOption("V", "version", "gerke-decoder version 2.0 preliminary 1");

		new SingleValueOption("o", "offset", "0");
		new SingleValueOption("l", "length", "-1");

		new SingleValueOption("F", "frange", "400,1200");
		new SingleValueOption("f", "freq", "-1");
		
		new SingleValueOption("w", "wpm", "15.0");

		new SingleValueOption("c", "clip", "-1");
		new SingleValueOption("q", "qtime", "0.10");
		new SingleValueOption("y", "sigma", "0.33");
		new SingleValueOption("z", "ctime", "0.30");

		new SingleValueOption("D", "dip-spike", "0.005,0.005");

		new SingleValueOption("u", "level", "1.0");
		new Flag("t", "timestamps");

		new Flag("S", "frequency-plot");
		
		new SingleValueOption("Z", "plot-interval", "0,-1");
		new Flag("P", "plot");
		new Flag("Q", "phase-plot");

		new SteppingOption("v", "verbose");

		// 0=false, 1=true
		// break-long-dashes, ...
		new SingleValueOption("H", "hidden-options", "1,1.7,8");

		new HelpOption(
				"h",
new String[]{
		"Usage is: java -jar gerke-decoder.jar [OPTIONS] WAVFILE",
		"Options are:",
		String.format("  -w WPM             WPM, tentative, defaults to %s", GerkeLib.getDefault("wpm")),
		String.format("  -F LOW,HIGH        Audio frequency search range, defaults to %s", GerkeLib.getDefault("frange")),
		String.format("  -f FREQ            Audio frequency, bypassing search"),
		String.format("  -c CLIPLEVEL       Clipping level, optional"),
		String.format("  -u ADJUSTMENT      Threshold adjustment, defaults to %s", GerkeLib.getDefault("level")),
		String.format("  -o OFFSET          Offset (seconds)"),
		String.format("  -l LENGTH          Length (seconds)"),
		
		String.format("  -q SAMPLE_PERIOD   sample period, defaults to %s TU", GerkeLib.getDefault("qtime")),
		
		String.format("  -y SIGMA           Gaussian sigma, defaults to %s TU", GerkeLib.getDefault("sigma")),
		String.format("  -z COHERENCE_TIME  Coherence time, defaults to %s TU", GerkeLib.getDefault("ctime")),
		
		String.format("  -D DIP,SPIKE       Limits for dip and spike removal, default: %s", GerkeLib.getDefault("dip-spike")),
		
		String.format("  -H PARAMETERS      Experimental parameters, default: %s", GerkeLib.getDefault("hidden-options")),
		
		String.format("  -S                 Generate frequency spectrum plot"),
		String.format("  -P                 Generate signal plot"),
		String.format("  -Q                 Generate phase angle plot"),

		String.format("  -Z START,LENGTH    Time interval for signal and phase plot (seconds)"),
		String.format("  -t                 Insert timestamps in decoded text"),
		String.format("  -v                 Verbosity (may be given several times)"),
		String.format("  -V                 Show version"),
		String.format("  -h                 This help"),
		"",
		"A tentative TU length (dot length) is derived from the tentative WPM value",
		"given in milliseconds as TU = 1200/WPM.",
		"",
		"The SAMPLE_PERIOD parameter defines the periodicity of signal evaluation",
		"as a fraction of the TU.",
		"",
		"The SIGMA parameter defines the width, as a fraction of the TU, of the",
		"Gaussian used in computing the signal value.",
		"",
		"The COHERENCE_TIME as a fraction of the TU is the time for which phase",
		"stability of the signal is required."
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

	private static String encodeLetter(int i) {
		return new String(new int[]{i}, 0, 1);
	}

	static short[] wav;

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

		Trans(int q, boolean rise) {
			this(q, rise, -1.0);
		}
		
		Trans(int q, boolean rise, double sigAcc) {
			this.q = q;
			this.rise = rise;
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
			final double wpm = GerkeLib.getDoubleOpt("wpm");
			final double tuMillis = 1200/wpm;
			new Info("dot time, tentative: %.3f ms", tuMillis);

			// tsLength is the relative TS length.
			// 0.10 is a typical value.
			// TS length in ms is: tsLength*tuMillis
			
			final double tsLength = GerkeLib.getDoubleOpt("qtime");
			
			final int framesPerSlice = (int) (tsLength*frameRate*tuMillis/1000.0);
			new Info("time slice: %.3f ms", 1000.0*framesPerSlice/frameRate);
			new Info("frames per time slice: %d", framesPerSlice);

			// Set number of frames to use from offset and length given in seconds
			final int length = GerkeLib.getIntOpt("length");
			
			if (GerkeLib.getIntOpt("offset") < 0) {
				new Death("offset cannot be negative");
			}
			final int offsetFrames = GerkeLib.getIntOpt("offset")*frameRate;
			final int nofFrames = length == -1 ? ((int) (frameLength - offsetFrames)) : length*frameRate;

			final int nofSlices = nofFrames/framesPerSlice;

			if (nofFrames < 0) {
				new Death("offset too large, WAV file length is: %f s", (double)frameLength/frameRate);
			}
			
			final double plotBegin;
			final double plotEnd;
			if (GerkeLib.getOptMultiLength("plot-interval") != 2) {
				new Death("bad plot interval: wrong number of suboptions");
			}
			
			final boolean phasePlot = GerkeLib.getFlag("phase-plot");
			if (GerkeLib.getFlag("plot") || phasePlot) {
				
				final double t1 = ((double) frameLength)/frameRate;
				
				final double t2 = (double) (GerkeLib.getIntOpt("offset"));
				
				final double t3 = 
						length == -1 ? t1 :
							dMin(t2 + length, t1);
				if (length != -1 && t2 + length > t1) {
					new Warning("offset+length exceeds %.1f seconds", t1);
				}
				
				final double t4 =
						dMax(GerkeLib.getDoubleOptMulti("plot-interval")[0], t2);
				if (t4 >= t3) {
					new Death("plot interval out of bounds");
				}
				else if (GerkeLib.getDoubleOptMulti("plot-interval")[0] < t2) {
					new Warning("starting plot interval at: %.1f s", t2);
				}
				
				final double t5 =
						GerkeLib.getDoubleOptMulti("plot-interval")[1] == -1.0 ?
								t3 :
									dMin(t3, t4 + GerkeLib.getDoubleOptMulti("plot-interval")[1]);
				if (GerkeLib.getDoubleOptMulti("plot-interval")[1] != -1.0 &&
						t4 + GerkeLib.getDoubleOptMulti("plot-interval")[1] > t3) {
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
			if (GerkeLib.getIntOpt("freq") != -1) {
				// frequency is specified on the command line
				if (GerkeLib.getFlag("frequency-plot")) {
					new Warning("frequency plot skipped when -f option given");
				}
				fBest = GerkeLib.getIntOpt("freq");
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

			final int clipLevelOverride = GerkeLib.getIntOpt("clip");
			
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
			
			final double level = GerkeLib.getDoubleOpt("level");
			new Info("relative tone/silence threshold: %.3f", level);

			new Debug("dash limit: %d, word space limit: %d", dashLimit, wordSpaceLimit);

			
			final double saRaw = signalAverage(fBest, frameRate, framesPerSlice, Short.MAX_VALUE);
			new Debug("signal average: %f", saRaw);
			final double sa = signalAverage(fBest, frameRate, framesPerSlice, clipLevel);
			new Debug("signal average clipped: %f", sa);
			
			final int histWidth = (int)(NOF_TU_FOR_CEILING_HISTOGRAM*tuMillis/(1000.0*framesPerSlice/frameRate));
			new Debug("histogram half-width (nof. slices): %d", histWidth);

			Node p = tree;

			final double[] sig = new double[nofSlices];

			// new folding summation
			// PARAMETER .. two of them
			final double tau = GerkeLib.getDoubleOpt("sigma")*tuMillis/1000;  // tau = sigma
			final double tauSegm = GerkeLib.getDoubleOpt("ctime")*tuMillis/1000;
			new Debug("sigma: %f", tau);
			new Debug("coherence time: %f", tauSegm);
			final double dt = 1.0/frameRate;


			// determine folding tables size
			// PARAMETER 0.01
			int foldSize = 0;
			for (int j = 0; true; j++) {
				double earg = j*dt/tau;
				final double w = Math.exp(-earg*earg/2);
				if (w < 0.001) {
					break;
				}
				else {
					foldSize++;
				}
			}
			double expTable[] = new double[foldSize];
			double sinTable[] = new double[foldSize];
			double cosTable[] = new double[foldSize];

			for (int j = 0; j < expTable.length; j++) {
				double earg = j*dt/tau;
				expTable[j] = Math.exp(-earg*earg);
				double angle = TWO_PI*fBest*j/frameRate;
				sinTable[j] = Math.sin(angle);
				cosTable[j] = Math.cos(angle);
			}

			double cutoff =
					GerkeLib.getDoubleOptMulti("hidden-options")[HiddenOptions.CUTOFF.ordinal()];
			
			int order = 2;   // PARAMETER 2
			
			final Butterworth buSin = new Butterworth();
			buSin.lowPass(order, (double)frameRate, cutoff* 1000.0/tuMillis);
			
			final Butterworth buCos = new Butterworth();
			buCos.lowPass(order, (double)frameRate, cutoff* 1000.0/tuMillis);
//			
//			
//			final ChebyshevI che1Sin = new ChebyshevI();
//			che1Sin.lowPass(order, (double) frameRate, cutoff*1000.0/tuMillis, 3);
//			
//			final ChebyshevI che1Cos = new ChebyshevI();
//			che1Cos.lowPass(order, (double) frameRate, cutoff*1000.0/tuMillis, 3);
			
//			final ChebyshevII che2Sin = new ChebyshevII();
//			che2Sin.lowPass(order, (double) frameRate, cutoff*1000.0/tuMillis, 3);
//			
//			final ChebyshevII che2Cos = new ChebyshevII();
//			che2Cos.lowPass(order, (double) frameRate, cutoff*1000.0/tuMillis, 3);
//			
//
//			final Bessel besSin = new Bessel();
//			besSin.lowPass(order, (double) frameRate, cutoff*1000.0/tuMillis);
//			
//			final Bessel besCos = new Bessel();
//			besCos.lowPass(order, (double) frameRate, cutoff*1000.0/tuMillis);
			
			final int aveSize = GerkeLib.getIntOptMulti("hidden-options")[HiddenOptions.AVE_SIZE.ordinal()];
			double[] av10 = new double[aveSize];
			for (int j = 0; j < aveSize; j++) { av10[j] = 0.0; }
			for (int q = 0; true; q++) {      //  q is sig[] index

				if (wav.length - q*framesPerSlice < framesPerSlice) {
					break;
				}
			
			    // feed the filters with wav samples
			    for (int k = -framesPerSlice+1; k <= 0; k++) {
			    	
			    	int wavIndex = q*framesPerSlice + k;
			    	
			    	double outSin = 0.0;
		    		double outCos = 0.0;
			    	if (wavIndex >= 0) {
			    		int ampRaw = wav[wavIndex];   // k is non-positive!
			    		final int amp = ampRaw < 0 ? iMax(-clipLevel, ampRaw) : iMin(clipLevel, ampRaw);

			    		double angle = TWO_PI*fBest*wavIndex/frameRate;
			    		outSin = buSin.filter(amp*Math.sin(angle));
			    		outCos = buCos.filter(amp*Math.cos(angle));
			    	}
					if (k == 0) {
						for (int kk = 0; kk < aveSize; kk++) {
							av10[kk] *= 0.8;
						}
						av10[q % aveSize] = Math.sqrt(outSin*outSin + outCos*outCos);
						double ss = 0.0;
						for (int j = 0; j < aveSize; j++) { ss += av10[j]; }
						sig[q] = ss/aveSize;
					}
			    }
//
//				double sinAcc = 0.0;
//				double cosAcc = 0.0;
//				double weightAcc = 0.0;
//				double sumWeightInSeg = 0.0;
//				double tauSegmMultiple = tauSegm;
//				double sigAcc = 0.0;
//				for (int j = 0; j < expTable.length; j++) {
//
//					if (q*framesPerSlice - j >= 0) {
//
//						if (j*dt > tauSegmMultiple) {
//							sigAcc += Math.sqrt(sinAcc*sinAcc + cosAcc*cosAcc);
//							tauSegmMultiple += tauSegm;
//							sinAcc = 0.0;
//							cosAcc = 0.0;
//							sumWeightInSeg += weightAcc;
//							weightAcc = 0.0;
//						}
//
//						int ampRaw = wav[q*framesPerSlice - j];
//						final int amp = ampRaw < 0 ?
//								iMax(-clipLevel, ampRaw) :
//									iMin(clipLevel, ampRaw);
//								sinAcc += expTable[j]*sinTable[j]*amp;
//								cosAcc += expTable[j]*cosTable[j]*amp;
//								weightAcc += expTable[j];
//					}
//				}
//
//				if (weightAcc != 0.0) {
//					sigAcc += Math.sqrt(sinAcc*sinAcc + cosAcc*cosAcc);
//					sumWeightInSeg += weightAcc;
//				}
//
//				sinAcc = 0.0;
//				cosAcc = 0.0;
//				weightAcc = 0.0;
//				
//				for (int j = 0; j < expTable.length; j++) {
//
//					if (j > 0 && q*framesPerSlice + j < wav.length) {
//
//						if (j*dt > tauSegmMultiple) {
//							sigAcc += Math.sqrt(sinAcc*sinAcc + cosAcc*cosAcc);
//							tauSegmMultiple += tauSegm;
//							sinAcc = 0.0;
//							cosAcc = 0.0;
//							sumWeightInSeg += weightAcc;
//							weightAcc = 0.0;
//						}
//
//						int ampRaw2 = wav[q*framesPerSlice + j];
//						final int amp2 = ampRaw2 < 0 ? iMax(-clipLevel, ampRaw2) : iMin(clipLevel, ampRaw2);
//
//						sinAcc -= expTable[j]*sinTable[j]*amp2;
//						cosAcc += expTable[j]*cosTable[j]*amp2;
//						weightAcc += expTable[j];
//					}
//
//				}
//
//				if (weightAcc != 0.0) {
//					sigAcc += Math.sqrt(sinAcc*sinAcc + cosAcc*cosAcc);
//					sumWeightInSeg += weightAcc;
//				}
//
//				sig[q] = sigAcc/sumWeightInSeg;
			}

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
			
			if (GerkeLib.getFlag("plot")) {
				pcSignal = new PlotCollector();
				plotEntries = new TreeMap<Double, List<PlotEntryBase>>();
			}
			else {
				pcSignal = null;
				plotEntries = null;
			}

			// iterate again over time slices

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
						trans[transIndex] = new Trans(q, true, dipAcc);
					}
					else {
						trans[transIndex] = new Trans(q, true);
					}
					transIndex++;
					tone = true;
					spikeAcc = squared(threshold - sigAverage);
				}
				else if (!newTone && tone) {
					// fall
					trans[transIndex] = new Trans(q, false, spikeAcc);
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
			final double dipLimit = GerkeLib.getDoubleOptMulti("dip-spike")[0];
			final int veryShortDip = (int) Math.round(0.2/tsLength);  // PARAMETER 0.2

			for (int t = 1; t < transIndex; t++) {
				if (transIndex > 0 &&
						trans[t].rise &&
						trans[t].dipAcc != -1.0 &&
						(trans[t].dipAcc < dipLimit*silentTu || trans[t].q - trans[t-1].q <= veryShortDip)) {
					new Debug("dip at: %d, width: %d, mass: %f, fraction: %f",
							t,
							trans[t].q - trans[t-1].q,
							trans[t].dipAcc,
							trans[t].dipAcc/silentTu);
					if (t+1 < transIndex) {
						// preserve accumulated spike value
						trans[t+1] =
								new Trans(trans[t+1].q,
										false,
										trans[t+1].spikeAcc + trans[t-1].spikeAcc);
					}
					trans[t-1] = null;
					trans[t] = null;
				}
				else if (transIndex > 0 &&
						trans[t].rise &&
						trans[t].dipAcc != -1.0 && t % 200 == 0) {
					new Debug("dip at: %d, width: %d, mass: %e, limit: %e",
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
				final double spikeLimit = GerkeLib.getDoubleOptMulti("dip-spike")[1];
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
					GerkeLib.getIntOptMulti("hidden-options")
					[HiddenOptions.BREAK_LONG_DASH.ordinal()] == 1;

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
						trans[t] = new Trans(q1, false, acc/2);
						trans[t+1] = new Trans(q2, true, acc/2);
						trans[t+2] = new Trans(q3, false, acc/2);
						t +=3;
					}
					else {
						t++;
					}
				}
			}

			if (GerkeLib.getFlag("plot")) {
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
			

			
			final int offset = GerkeLib.getIntOpt("offset");
			for (int t = 0; t < transIndex; t++) {

				final boolean newTone = trans[t].rise;

				if (!prevTone && newTone) {
					// silent -> tone
					if (t == 0) {
						p = tree;
					}
					else if (trans[t].q - trans[t-1].q > wordSpaceLimit) {
						if (GerkeLib.getFlag("timestamps")) {
							formatter.add(true,
									p.text,
									offset + (int) Math.round(trans[t].q*tsLength*tuMillis/1000));
						}
						else {
							formatter.add(true, p.text, -1);
						}
						nTuTotal += p.nTus + 7;
						p = tree;
					}
					else if (trans[t].q - trans[t-1].q > charSpaceLimit) {
						formatter.add(false, p.text, -1);
						nTuTotal += p.nTus + 3;
						nTuSpace += 3;
						qSpace += (trans[t].q - qBeginSilence);
						p = tree;
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
			
			new Info("decoded text MD5 digest: %s", formatter.getDigest());

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
		
		final int nofSubOptions = GerkeLib.getOptMultiLength("frange");
		if (nofSubOptions != 2) {
			new Death("expecting 2 suboptions, got: %d", nofSubOptions);
			
		}
		final int f0 = GerkeLib.getIntOptMulti("frange")[0];
		final int f1 = GerkeLib.getIntOptMulti("frange")[1];
		new Debug("search for frequency in range: %d to %d", f0, f1);

		final SortedMap<Integer, Double> pairs =
				GerkeLib.getFlag("frequency-plot") ? new TreeMap<Integer, Double>() : null;
		
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
		if (GerkeLib.getIntOpt("verbose") >= 2) {
			new Info("version: %s", GerkeLib.getOpt("version"));
			new Info("WPM, tentative: %f", GerkeLib.getDoubleOpt("wpm"));
			new Info("frequency: %d", GerkeLib.getIntOpt("freq"));
			new Info("f0,f1: %s", GerkeLib.getOpt("frange"));
			new Info("offset: %d", GerkeLib.getIntOpt("offset"));
			new Info("length: %d", GerkeLib.getIntOpt("length"));
			new Info("clipLevel: %d", GerkeLib.getIntOpt("clip"));
			
			new Info("sampling period: %f", GerkeLib.getDoubleOpt("qtime"));
			new Info("sigma: %f", GerkeLib.getDoubleOpt("sigma"));
			new Info("coherence time: %f", GerkeLib.getDoubleOpt("ctime"));
			
			new Info("level: %f", GerkeLib.getDoubleOpt("level"));
			new Info("frequency plot: %b", GerkeLib.getFlag("frequency-plot"));
			new Info("signal plot: %b", GerkeLib.getFlag("plot"));
			new Info("phase plot: %b", GerkeLib.getFlag("phase-plot"));
			new Info("plot interval: %s", GerkeLib.getOpt("plot-interval"));
			new Info("timestamps: %b", GerkeLib.getFlag("timestamps"));
			new Info("hidden: %s", GerkeLib.getOpt("hidden-options"));
			new Info("verbose: %d", GerkeLib.getIntOpt("verbose"));

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
			int points = (int)Math.round(500/(1 + squared(3*x/width)));   // PARAMETER 3
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
			int points = (int)Math.round(500/(1 + squared(4*x/width)));
			hist[index] += points;
			sumPoints += points;
		}
		
		// remove the high-signal counts
		final double remFrac = 0.50; // hard-coded PARAMETER 0.50; larger value removes more
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
			new Debug("clip level: %d, signal: %f", midpoint, uNew);
			
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

}
