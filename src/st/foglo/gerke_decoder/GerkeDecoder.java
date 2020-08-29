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
import java.util.Locale;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;

import st.foglo.gerke_decoder.GerkeLib.*;

public final class GerkeDecoder {
	
	static final double twoPi = 2*Math.PI;

	static {
		new VersionOption("V", "version", "gerke-decoder version 1.5-antispike-0.4");

		new SingleValueOption("w", "wpm", "15.0");
		new SingleValueOption("f", "freq", "-1");
		new SingleValueOption("F", "frange", "400,1200");
		new SingleValueOption("o", "offset", "0");
		new SingleValueOption("l", "length", "-1");
		new SingleValueOption("L", "length", "-1"); // deprecated
		new SingleValueOption("c", "clip", "-1");
		new SingleValueOption("u", "level", "1.0");
		
		new SingleValueOption("X", "detpar", "0.127,0.8");
		
		new Flag("P", "plot");
		new Flag("Q", "phase-plot");
		new SingleValueOption("Z", "plot-interval", "0.0,99999.9");
		
		new SteppingOption("v", "verbose");
		new HelpOption(
				"h",
new String[]{
		"Usage is: java -jar gerke-decoder.jar [OPTIONS] WAVFILE",
		"Options are:",
		String.format("  -w WPM          WPM, tentative, defaults to %s", GerkeLib.getDefault("wpm")),
		String.format("  -F LOW,HIGH     audio frequency search range, defaults to %s", GerkeLib.getDefault("frange")),
		String.format("  -f FREQ         audio frequency, bypassing search"),
		String.format("  -c CLIPLEVEL    clipping level, optional"),
		String.format("  -u ADJUSTMENT   threshold adjustment, defaults to %s", GerkeLib.getDefault("level")),
		String.format("  -o OFFSET       offset (seconds)"),
		String.format("  -l LENGTH       length (seconds)"),
		String.format("  -X TS,WIN       detection parameters, default: %s", GerkeLib.getDefault("detpar")),
		String.format("  -P              Generate signal plot (requires gnuplot)"),
		String.format("  -Q              Generate phase angle plot (requires gnuplot)"),
		String.format("  -Z BEGIN,END    Restrict plot to time interval (seconds)"),
		String.format("  -v              verbosity (may be given several times)"),
		String.format("  -V              show version"),
		String.format("  -h              this help"),
		"",
		"A tentative TU length (dot length) is derived from the tentative WPM value",
		"given in milliseconds as TU = 1200/WPM.",
		"",
		"The TS parameter defines a time slice length as a fraction of the TU. The",
		"signal strength is evaluated in each time slice.",
		"",
		"The WIN parameter defines a sliding window width as a fraction of the TU."
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
	static int wavIndex = 0;
	
	/**
	 * 
	 * @param ss
	 * @param offset
	 * @param n
	 * @return
	 */
	static int wavRead(short[] ss, int offset, int n) {
		
		final int nActual = Math.min(n, wav.length - wavIndex);
		for (int k = 0; k < nActual; k++) {
			ss[offset + k] = wav[wavIndex + k];
		}
		wavIndex += nActual;
		return nActual;
	}
	
	static void wavReset() {
		wavIndex = 0;
	}

	static class TrigTable {
		final double[] sines;
		final double[] coses;
		
		TrigTable(int f, int nFrames, int frameRate) {
			this.sines = new double[nFrames];
			this.coses = new double[nFrames];
			for (int j = 0; j < nFrames; j++) {
				final double angle = twoPi*f*j/frameRate;
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
		
		void add(boolean wordBreak, String text) {
			sb.append(text);
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
				md.update(sp);
				md.update(sb.toString().getBytes(Charset.forName("UTF-8")));
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
				add(true, "");
			}
		}
	}
	
	
	static class PlotCollector {
		String fileName;
		String fileNameWin;
		PrintStream ps;
		
		PlotCollector() throws IOException {

			this.fileName = makeTempFile();
			this.fileNameWin = isWindows() ? toWindows(fileName) : null;
			this.ps = new PrintStream(
					new File(fileNameWin != null ? fileNameWin : fileName));
		}
		
		void plot(String renderMode, int nofCurves) throws IOException, InterruptedException {
			ps.close();
			doGnuplot(fileName, nofCurves, renderMode);
			Files.delete(
					(new File(fileNameWin != null ? fileNameWin : fileName)).toPath());
		}
		
		/**
		 * Invoke Gnuplot
		 * 
		 * @param tempFileName
		 * @param nofCurves
		 * @param renderMode   "lines"|"points"
		 * @throws IOException
		 * @throws InterruptedException
		 */
		void doGnuplot(
				String tempFileName,
				int nofCurves,
				String renderMode) throws IOException, InterruptedException {
			final ProcessBuilder pb =
					new ProcessBuilder(
							isWindows() ? "gnuplot-X11" : "gnuplot",
							"--persist",
							"-e",
							"set term x11 size 1400 200",
							"-e",
							nofCurves == 2 ? 
									String.format("plot '%s' using 1:2 with %s, '%s' using 1:3 with %s",
											tempFileName,
											renderMode,
											tempFileName,
											renderMode) :
									String.format("plot '%s' using 1:2 with %s",
											tempFileName,
											renderMode)
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
	 * This class can perhaps be eliminated. The way the Trans[] array
	 * is created it is known that every second instance will have
	 * rise = true, and the others will have rise = false.
	 */
	static class Trans {
		final int q;
		final boolean rise;

		Trans(int q, boolean rise) {
			this.q = q;
			this.rise = rise;
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
			// 0.125 is a typical value.
			// TS length in ms is: tsLength*tuMillis
			final double tsLength = GerkeLib.getDoubleOptMulti("detpar")[0];
			final int framesPerSlice = (int) (tsLength*frameRate*tuMillis/1000.0);
			new Info("time slice: %.3f ms", 1000.0*framesPerSlice/frameRate);
			new Info("frames per time slice: %d", framesPerSlice);

			// Number of terms in sliding window
			final int windowSize =
					Math.max(1,
							(int)Math.round(
									GerkeLib.getDoubleOptMulti("detpar")[1]*tuMillis*frameRate/(1000.0*framesPerSlice)));
			new Info("sliding window nof. slots: %d", windowSize);

			// Set number of frames to use from offset and length given in seconds
			final int length = GerkeLib.getIntOpt("length");
			final int offsetFrames = GerkeLib.getIntOpt("offset")*frameRate;
			final int nofFrames = 
					length == -1 ? ((int) (frameLength - offsetFrames)) : length*frameRate;

			if (nofFrames < 0) {
				new Death("offset too large, WAV file length is: %f s", (double)frameLength/frameRate);
			}
			
			final double plotBegin;
			final double plotEnd;
			if (GerkeLib.getOptMultiLength("plot-interval") != 2) {
				new Death("bad plot interval");
			}
			if (GerkeLib.getFlag("plot") || GerkeLib.getFlag("phase-plot")){

				plotBegin =
						Math.max(GerkeLib.getIntOpt("offset"),
								GerkeLib.getDoubleOptMulti("plot-interval")[0]);
				plotEnd =
						Math.min((double)frameLength/frameRate,
								GerkeLib.getDoubleOptMulti("plot-interval")[1]);
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
			
			final long dashLimit = Math.round(1.9*tuMillis*frameRate/(1000*framesPerSlice));        // PARAMETER
			final long wordSpaceLimit = Math.round(5.2*tuMillis*frameRate/(1000*framesPerSlice));   // PARAMETER
			final long charSpaceLimit = Math.round(1.4*tuMillis*frameRate/(1000*framesPerSlice));   // PARAMETER
			final long twoDashLimit = Math.round(5.0*tuMillis*frameRate/(1000*framesPerSlice));     // PARAMETER
			
			final double level = GerkeLib.getDoubleOpt("level");
			new Info("relative tone/silence threshold: %.3f", level);

			new Debug("dash limit: %d, word space limit: %d", dashLimit, wordSpaceLimit);

			
			final double saRaw = signalAverage(fBest, frameRate, framesPerSlice, Short.MAX_VALUE);
			new Debug("signal average: %f", saRaw);
			final double sa = signalAverage(fBest, frameRate, framesPerSlice, clipLevel);
			new Debug("signal average clipped: %f", sa);
			
			// hardcoded PARAMETER 50
			final int histWidth = (int)(50*tuMillis/(1000.0*framesPerSlice/frameRate));
			new Debug("histogram half-width (nof. slices): %d", histWidth);

			final double[] amps = new double[windowSize];
			int ampIndex = 0;
			Node p = tree;

			final short[] shorts = new short[framesPerSlice];

			// compute an array holding amplitude per time slice
			final double[] sig = new double[nofFrames/framesPerSlice];
			final double[] cosSum = new double[nofFrames/framesPerSlice];
			final double[] sinSum = new double[nofFrames/framesPerSlice];
			final double[] wphi = new double[nofFrames/framesPerSlice];
			
			final boolean phasePlot = GerkeLib.getFlag("phase-plot");
			
			wavReset();

			final TrigTable trigTable =
					phasePlot? null :
						new TrigTable(fBest, framesPerSlice, frameRate);
			
			for (int q = 0; true; q++) {
				
				final int sRead = wavRead(shorts, 0, framesPerSlice);
				if (sRead < framesPerSlice) {
					break;
				}
				
				// needed only if phase plot requested
				final double angleOffset =
						twoPi*fBest*timeSeconds(q, framesPerSlice, frameRate, offsetFrames);
				double sinAcc = 0.0;
				double cosAcc = 0.0;
				for (int j = 0; j < framesPerSlice; j++) {
					// j is frame index
					final int ampRaw = shorts[j];
					final int amp = ampRaw < 0 ?
							Math.max(-clipLevel, ampRaw) :
								Math.min(clipLevel, ampRaw);

					if (phasePlot) {
						final double angle = angleOffset + twoPi*fBest*j/frameRate;
						sinAcc += Math.sin(angle)*amp;
						cosAcc += Math.cos(angle)*amp;
					}
					else {
						sinAcc += trigTable.sin(j)*amp;
						cosAcc += trigTable.cos(j)*amp;
					}
				}

				sig[q] = Math.sqrt(sinAcc*sinAcc + cosAcc*cosAcc)/framesPerSlice;
				
				cosSum[q] = cosAcc;
				sinSum[q] = sinAcc;
			}
			
			for (int q = 0; q < wphi.length; q++) {
				// compute per-timeslice phase
				wphi[q] = wphi(q, cosSum, sinSum, sig, level, histWidth);
			}

			if (phasePlot) {
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
				pcPhase.plot("points", 1);
			}

			PlotCollector pcSignal =
					GerkeLib.getFlag("plot") ? new PlotCollector() : null;

			wavReset();
			final Trans[] trans = new Trans[nofFrames/framesPerSlice];
			int transIndex = 0;
			boolean tone = false;
			for (int q = 0; true; q++) {
				final int sRead = wavRead(shorts, 0, framesPerSlice);
				if (sRead < framesPerSlice) {
					// assume end of stream, drop the very final time slice
					break;
				}
				
				// insert new value to sliding window
				amps[ampIndex % amps.length] = sig[q];
				ampIndex++;

				// compute average
				double rAve = 0.0;
				int nofEntries = 0;
				for (int k = 0; k < Math.min(amps.length, ampIndex); k++) {
					rAve += amps[k];
					nofEntries++;
				}
				rAve = rAve/nofEntries;
				
				// hard-coded PARAMETER 0.630, duplicated below
				final double threshold = level*0.630*localAmpByHist(q, sig, histWidth);
				final boolean newTone = rAve > threshold;

				if (pcSignal != null) {
					final double seconds = timeSeconds(q, framesPerSlice, frameRate, offsetFrames);
					if (plotBegin <= seconds && seconds <= plotEnd) {
						pcSignal.ps.println(String.format("%f %f %f", seconds, rAve, threshold));
					}
				}
				
				if (newTone && !tone) {
					trans[transIndex] = new Trans(q, true);
					transIndex++;
					tone = true;
				}
				else if (!newTone && tone) {
					trans[transIndex] = new Trans(q, false);
					transIndex++;
					tone = false;
				}
			}
			
			// Eliminate triplets
			// hardcoded PARAMETER 0.3
			final int tripletLimit = (int)Math.round(0.5/tsLength);
			int tripletCount = 0;
			
			for (int t = 1; t < transIndex-1; t++) {
				if (trans[t-1] != null && trans[t] != null && trans[t+1] != null &&
						((trans[t-1].rise && !trans[t].rise && trans[t+1].rise) ||
								(!trans[t-1].rise && trans[t].rise && !trans[t+1].rise)) &&
						trans[t+1].q - trans[t-1].q <= tripletLimit) {
					new Debug("triplet at: %d, width: %d", t, trans[t+1].q - trans[t-1].q);
					trans[t-1] = null;
					trans[t] = new Trans(trans[t].q, !trans[t].rise);
					trans[t+1] = null;
					tripletCount++;
				}
			}
			new Debug("triplet limit: %d", tripletLimit);
			new Debug("nof. triplets: %d", tripletCount);
			
			transIndex = removeHoles(trans, transIndex);
			
			// Eliminate doublets
			// a too wide doublet limit causes dashes to break into two dots,
			// maybe apply a wider limit for positive spikes and a narrow one
			// for negative amplitude drops
			final int doubletLimit = (int)Math.round(0.30/tsLength);
			int doubletCount = 0;
			for (int t = 1; t < transIndex; t++) {
				if (trans[t-1] != null && trans[t] != null &&
						((trans[t-1].rise && !trans[t].rise) ||
								(!trans[t-1].rise && trans[t].rise)) &&
						trans[t].q - trans[t-1].q <= doubletLimit) {
					new Debug("doublet at: %d, width: %d", t, trans[t].q - trans[t-1].q);
					trans[t-1] = null;
					trans[t] = null;
					doubletCount++;
				}
			}
			new Debug("doublet limit: %d", doubletLimit);
			new Debug("nof. doublets: %d", doubletCount);

			transIndex = removeHoles(trans, transIndex);
			
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
			
			for (int t = 0; t < transIndex; t++) {

				final boolean newTone = trans[t].rise;

				if (!prevTone && newTone) {
					// silent -> tone
					if (t == 0) {
						p = tree;
					}
					else if (trans[t].q - trans[t-1].q > wordSpaceLimit) {
						formatter.add(true, p.text);
						nTuTotal += p.nTus + 7;
						p = tree;
					}
					else if (trans[t].q - trans[t-1].q > charSpaceLimit) {
						formatter.add(false, p.text);
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
					if (dashSize > twoDashLimit) {
						new Debug("breaking up too long dash");
						if (p.dash == null) {
							final String newCode = p.code + "-";
							p.dash = new Node(null, newCode);
						}
						p = p.dash;
						
						if (p.dash == null) {
							final String newCode = p.code + "-";
							p.dash = new Node(null, newCode);
						}
						p = p.dash;
					}
					else if (dashSize > dashLimit) {
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
				formatter.add(true, p.text);
				nTuTotal += p.nTus;
				formatter.newLine();
			}
			else if (p == tree && formatter.getPos() > 0) {
				formatter.flush();
				formatter.newLine();
			}
			
			new Info("decoded text MD5 digest: %s", formatter.getDigest());

			if (pcSignal != null) {
				pcSignal.plot("lines", 2);
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
		for (int j = Math.max(0, k-width); j <= Math.min(len-1, k+width); j++) {
			final double amp = sig[j];
			ampAve += amp;
			m++;
			sumx += amp*amp*x[j];
			sumy += amp*amp*y[j];
		}
		ampAve = ampAve/m;
		
		if (ampAve < 0.66*level*localAmpByHist(k, sig, histWidth)) {
			return 0.0;
		}
		else {
			return Math.atan2(sumy, sumx);
		}
	}

	private static int findFrequency(int frameRate, int framesPerSlice) {
		
		final int nofSubOptions = GerkeLib.getOptMultiLength("frange");
		if (nofSubOptions != 2) {
			new Death("expecting 2 suboptions, got: %d", nofSubOptions);
			
		}
		final int f0 = GerkeLib.getIntOptMulti("frange")[0];
		final int f1 = GerkeLib.getIntOptMulti("frange")[1];
		new Debug("search for frequency in range: %d to %d", f0, f1);
		
		// search in steps of 10 Hz
		int fBest = -1;
		final short[] shorts = new short[framesPerSlice];
		double rSquaredSumBest = -1.0;
		for (int f = f0; f <= f1; f += 10) {
			final double rSquaredSum = r2Sum(f, frameRate, framesPerSlice, shorts);
			if (rSquaredSum > rSquaredSumBest) {
				rSquaredSumBest = rSquaredSum;
				fBest = f;
			}
		}
		// refine, steps of 1 Hz
		final int g0 = fBest - 12;
		final int g1 = fBest + 12;
		for (int f = g0; f <= g1; f += 1) {
			final double rSquaredSum = r2Sum(f, frameRate, framesPerSlice, shorts);
			if (rSquaredSum > rSquaredSumBest) {
				rSquaredSumBest = rSquaredSum;
				fBest = f;
			}
		}
		
		if (fBest == g0 || fBest == g1) {
			new Warning("frequency may not be optimal, try a wider range");
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
			new Info("level: %f", GerkeLib.getDoubleOpt("level"));
			new Info("detection parameters: %s", GerkeLib.getOpt("detpar"));
			new Info("signal plot: %b", GerkeLib.getFlag("plot"));
			new Info("phase plot: %b", GerkeLib.getFlag("phase-plot"));
			new Info("plot interval: %s", GerkeLib.getOpt("plot-interval"));
			new Info("verbose: %d", GerkeLib.getIntOpt("verbose"));

			for (int k = 0; k < GerkeLib.nofArguments(); k++) {
				new Info("argument %d: %s", k+1, GerkeLib.getArgument(k));
			}
		}
	}

	private static double localAmpByHist(int q, double[] sig, int width) {

		int q1 = Math.max(q-width, 0);
		int q2 = Math.min(q+width, sig.length);
		
		// find the maximal sig value
		double sigMax = -1.0;
		for (int j = q1; j < q2; j++) {
			sigMax = Math.max(sig[j], sigMax);
		}
		
		// produce a histogram with 100 slots
		final int[] hist = new int[100];
		for (int j = q1; j < q2; j++) {
			final int index = (int) Math.round(99*sig[j]/sigMax);
			hist[index] += 1;
		}
		
		// remove the low-amp counts
		// TODO, consider removing more than 50%
		// hardcoded PARAMETER 2
		int count = (q2-q1)/2;
		for (int k = 0; k < 100; k++) {
			final int decr = Math.min(hist[k], count);
			hist[k] -= decr;
			count -= decr;
		}
		
		// now produce a weighted average
		int sumCount = 0;
		double sumAmp = 0.0;
		
		for (int k = 0; k < 100; k++) {
			sumCount += hist[k];
			sumAmp += hist[k]*(k/100.0)*sigMax;
		}
		
		return sumAmp/sumCount;
	}

	private static double r2Sum(int f, int frameRate, int framesPerSlice, short[] shorts) {
		
		double rSquaredSum = 0.0;
		final TrigTable trigTable = new TrigTable(f, framesPerSlice, frameRate);
		wavReset();
		for (; true;) {
			final int sRead = wavRead(shorts, 0, framesPerSlice);
			if (sRead < framesPerSlice) {
				return rSquaredSum;
			}
			double sinAcc = 0.0;
			double cosAcc = 0.0;
			for (int j = 0; j < framesPerSlice; j++) {
				final short amp = shorts[j];
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

		wavReset();
		final short[] shorts = new short[framesPerSlice];
		double rSum = 0.0;
		int divisor = 0;
		for (; true;) {
			final int sRead = wavRead(shorts, 0, framesPerSlice);
			if (sRead < framesPerSlice) {
				break;
			}
			
			double sinAcc = 0.0;
			double cosAcc = 0.0;

			for (int j = 0; j < framesPerSlice; j++) {
				final int ampRaw = (int) shorts[j];
				final int amp = ampRaw < 0 ? Math.max(ampRaw, -clipLevel) : Math.min(ampRaw, clipLevel);
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
}
