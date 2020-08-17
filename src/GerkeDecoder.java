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
	
	static {
		new VersionOption("V", "version", "gerke-decoder version 1.3");

		new SingleValueOption("w", "wpm", "15.0");
		new SingleValueOption("f", "freq", "-1");
		new SingleValueOption("F", "frange", "400,1200");
		new SingleValueOption("o", "offset", "0");
		new SingleValueOption("L", "length", "-1");
		new SingleValueOption("c", "clip", "-1");
		new SingleValueOption("u", "level", "1.0");
		
		new SingleValueOption("X", "detpar", "0.127,0.8");

		new Flag("P", "plot");
		
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
		String.format("  -o OFFSET       offset (in seconds)"),
		String.format("  -L LENGTH       length (in seconds)"),
		String.format("  -X TS,WIN       detection parameters, default: %s", GerkeLib.getDefault("detpar")),
		String.format("  -P              Generate signal plot (requires gnuplot)"),
		String.format("  -v              verbosity (may be given several times)"),
		String.format("  -V              show version"),
		String.format("  -h              this help"),
		"",
		"The TS parameter defines the time slice length as a fraction of the TU length.",
		"The WIN parameter defines the sliding window size as a fraction of the TU length."
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
		new Node(encodeLetter(252), "..--");    // ü
		new Node("l", ".-..");
		new Node(encodeLetter(228), ".-.-");    // ä
		new Node("p", ".--.");
		new Node("j", ".---");
		new Node("b", "-...");
		new Node("x", "-..-");
		new Node("c", "-.-.");
		new Node("y", "-.--");
		new Node("z", "--..");
		new Node("q", "--.-");
		new Node(encodeLetter(246), "---.");    // ö
		new Node("ch", "----");
		
		new Node(encodeLetter(233), "..-..");   // é
		new Node(encodeLetter(229), ".--.-");   // å

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
	
//	/**
//	 * Returns an integer that is close to (and greater than) the intended
//	 * number of time slices per second. The returned value is also a
//	 * divisor of the frame rate.
//	 * 
//	 * @param slicesPerTu
//	 * @param tuMillis
//	 * @param frameRate
//	 * @return
//	 */
//	private static int frac(int slicesPerTu, double tuMillis, int frameRate) {
//		final SortedSet<Integer> s = new TreeSet<Integer>();
//		if (frameRate == 44100) {
//			for (int i = 1; i <= 2; i += (2-1)) {
//				for (int j = 1; j <= 3; j += (3-1)) {
//					for (int k = 1; k <= 3; k += (3-1)) {
//						for (int m = 1; m <= 5; m += (5-1)) {
//							for (int n = 1; n <= 5; n += (5-1)) {
//								for (int o = 1; o <= 7; o += (7-1)) {
//									for (int p = 1; p <= 7; p += (7-1)) {
//										s.add(new Integer(i*j*k*m*n*o*p));
//									}
//								}
//							}
//						}
//					}
//				}
//			}
//		}
//		else if (frameRate == 12000) {
//			for (int i = 1; i <= 2; i += (2-1)) {
//				for (int j = 1; j <= 2; j += (2-1)) {
//					for (int k = 1; k <= 2; k += (2-1)) {
//						for (int m = 1; m <= 2; m += (2-1)) {
//							for (int n = 1; n <= 3; n += (3-1)) {
//								for (int o = 1; o <= 5; o += (5-1)) {
//									for (int p = 1; p <= 5; p += (5-1)) {
//										for (int q = 1; p <= 5; p += (5-1)) {
//											s.add(new Integer(i*j*k*m*n*o*p*q));
//										}
//									}
//								}
//							}
//						}
//					}
//				}
//			}
//		}
//		else if (frameRate == 16384) {
//			for (int j = 2; j <= 8192; j *= 2) {
//				s.add(new Integer(j));
//			}
//		}
//		else {
//			new Death("cannot handle frame rate: %d", frameRate);
//		}
//		
//		for (Integer u : s) {
//			if (Double.valueOf(u.intValue()) > slicesPerTu*1000/tuMillis) {
//				return u.intValue();
//			}
//		}
//		new Death("no frac for tu: %e ms", tuMillis);
//		return -1;
//	}

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
			new Info("nof. frames: %d", frameLength);

			// Get the tentative WPM
			final double wpm = GerkeLib.getDoubleOpt("wpm");
			final double tuMillis = 1200/wpm;
			new Info("dot time, tentative: %f ms", tuMillis);

			// tsLength is the relative TS length.
			// 0.125 is a typical value.
			// TS length in ms is: tsLength*tuMillis
			final double tsLength = GerkeLib.getDoubleOptMulti("detpar")[0];
			final int framesPerSlice = (int) (tsLength*frameRate*tuMillis/1000.0);
			new Info("time slice: %f ms", 1000.0*framesPerSlice/frameRate);
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
				new Death("offset too large, WAV file length is: %d s", frameLength/frameRate);
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
			
			final long dashLimit = Math.round(1.7*tuMillis*frameRate/(1000*framesPerSlice));        // PARAMETER
			final long wordSpaceLimit = Math.round(5.1*tuMillis*frameRate/(1000*framesPerSlice));   // PARAMETER
			final long charSpaceLimit = Math.round(1.4*tuMillis*frameRate/(1000*framesPerSlice));   // PARAMETER
			final long twoDashLimit = Math.round(5.0*tuMillis*frameRate/(1000*framesPerSlice));     // PARAMETER
			final long spikeLimit = Math.round(0.17*tuMillis*frameRate/(1000*framesPerSlice));      // PARAMETER
			
			final double level = GerkeLib.getDoubleOpt("level");

			new Debug("dash limit: %d, word space limit: %d", dashLimit, wordSpaceLimit);

			
			final double saRaw = signalAverage(fBest, frameRate, framesPerSlice, Short.MAX_VALUE);
			new Debug("signal average: %f", saRaw);
			final double sa = signalAverage(fBest, frameRate, framesPerSlice, clipLevel);
			new Debug("signal average clipped: %f", sa);

			// using the frequency, and a time slice, and a frame time, 
			// draw silent/tone

			final double[] sines = new double[framesPerSlice];
			final double[] coses = new double[framesPerSlice];
			for (int j = 0; j < framesPerSlice; j++) {
				sines[j] = Math.sin(2*Math.PI*fBest*j*1/frameRate);
				coses[j] = Math.cos(2*Math.PI*fBest*j*1/frameRate);
			}

			boolean tone = false;
			int dashSize = 0;
			int silenceSize = 0;
			final double[] amps = new double[windowSize];
			int ampIndex = 0;
			Node p = tree;

			final short[] shorts = new short[framesPerSlice];
			int nFrames = 0;
			int charStart = -1;
			int lastToneToSilent = 0;

			int tuCharAcc = 0;           // counters for dot equivalents
			int tuCharSpaceAcc = 0;
			int tuWordSpaceAcc = 0;
			
			int timeCharAcc = 0;         // time accumulators
			int timeCharSpaceAcc = 0;
			int timeWordSpaceAcc = 0;

			// compute an array holding amplitude per time slice.
			// this array is much smaller than the frames array.

			wavReset();
			final double[] sig = new double[nofFrames/framesPerSlice];
			for (int q = 0; true; q++) {
				
				final int sRead = wavRead(shorts, 0, framesPerSlice);
				if (sRead < framesPerSlice) {
					// assume end of stream
					break;
				}
				
				double sinAcc = 0.0;
				double cosAcc = 0.0;
				final int frames = framesPerSlice;
				for (int j = 0; j < frames; j++) {                    // for each frame
					// j is frame index, now get frame amplitudes
					final int ampRaw = shorts[j];
					final int amp = ampRaw < 0 ? Math.max(-clipLevel, ampRaw) : Math.min(clipLevel, ampRaw);
					sinAcc += sines[j]*amp;
					cosAcc += coses[j]*amp;
				}

				sig[q] = Math.sqrt(sinAcc*sinAcc + cosAcc*cosAcc)/frames;
			}
			
			String tempFileName = null;
			String tempFileNameJava = null;
			PrintStream plos = null;
			
			if (GerkeLib.getFlag("plot")) {
				// generate a temporary file
				tempFileName = makeTempFile();
				new Info("temp file: %s", tempFileName);

				// Cygwin specific
				tempFileNameJava = toWindows(tempFileName);
				new Info("windows path: %s", tempFileNameJava);
				
				plos = new PrintStream(new File(tempFileNameJava));
			}

			int lastTransition = -((int) spikeLimit);
			
			final Formatter word = new Formatter();
			wavReset();
			for (int q = 0; true; q++) {
				
				final int sRead = wavRead(shorts, 0, framesPerSlice);
				if (sRead < framesPerSlice) {
					// assume end of stream, drop the very final time slice
					break;
				}

				nFrames += framesPerSlice;    // current time is (nFrames*1000.0/frameRate) ms

				amps[ampIndex] = sig[q];
				ampIndex = (ampIndex + 1) % amps.length;

				double rAve = amps[0];
				for (int k = 1; k < amps.length; k++) {
					rAve += amps[k];
				}
				rAve = rAve/amps.length;
				
				final double thresh = 0.66*level*localAmpByHist(q, sig);    // hard-coded PARAMETER 0.66
				final boolean newTone = rAve > thresh;
				
				if (GerkeLib.getFlag("plot")) {
					final double seconds = offsetFrames*(1.0/frameRate) + ((double)q)*framesPerSlice/frameRate;
					plos.println(String.format("%f %f %f", seconds, rAve, thresh));
				}
				
				
				if (!tone && !newTone) {
					silenceSize++;
				}
				else if (tone && newTone) {
					dashSize++;
				}
				else if (!tone && newTone && q - lastTransition > spikeLimit) {
					// silent -> tone
					lastTransition = q;
					if (silenceSize > wordSpaceLimit) {
						// TODO, refactor, lots of duplication

						if (charStart != -1) {
							word.add(true, p.text);
						}
						
						if (charStart != -1) {
							timeWordSpaceAcc += lastToneToSilent - charStart;
							tuCharAcc += p.nTus;
							
							tuWordSpaceAcc += 7;
							timeWordSpaceAcc += nFrames - lastToneToSilent;
							
							charStart = nFrames;
						}
						
						p = tree;
						charStart = nFrames;
					}
					else if (silenceSize > charSpaceLimit) {
						if (charStart != -1) {
							word.add(false, p.text);
						}
						
						if (charStart != -1) {
							timeCharSpaceAcc += lastToneToSilent - charStart;
							tuCharAcc += p.nTus;
							
							tuCharSpaceAcc += 3;
							timeCharSpaceAcc += nFrames - lastToneToSilent;
							
							charStart = nFrames;
						}

						p = tree;
						charStart = nFrames;
					}
					dashSize = 1;
				}
				else if (tone && !newTone && q - lastTransition > spikeLimit) {
					// tone -> silent
					lastTransition = q;
					if (dashSize > twoDashLimit) {
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
					lastToneToSilent = nFrames;
					silenceSize = 0;
				}
				tone = newTone;
			}

			if (p != tree) {
				word.add(true, p.text);
				word.newLine();
			} 
			else if (p == tree && word.getPos() > 0) {
				word.newLine();
			}
			
			new Info("MD5 digest: %s", word.getDigest());

			if (GerkeLib.getFlag("plot")) {
				// close the file
				plos.close();
				
				// create the plot
				doGnuplot(tempFileName);
				
				// remove the file
				Files.delete((new File(tempFileNameJava)).toPath());
			}
			
			
			final double tuEffMillisActual =
					(timeCharAcc + timeCharSpaceAcc + timeWordSpaceAcc)*(1000.0/frameRate)/(tuCharAcc + tuCharSpaceAcc + tuWordSpaceAcc);
			
			new Info("TUs: %d, frames: %d, TU: %f ms, WPM eff: %f",
					tuCharAcc + tuCharSpaceAcc + tuWordSpaceAcc,
					timeCharAcc + timeCharSpaceAcc + timeWordSpaceAcc,
					tuEffMillisActual,
					1200.0/tuEffMillisActual);
			
			new Info("char spaces: %f", ((timeCharSpaceAcc*(1000.0/frameRate)/tuCharSpaceAcc)/tuEffMillisActual));
			new Info("word spaces: %f", ((timeWordSpaceAcc*(1000.0/frameRate)/tuWordSpaceAcc)/tuEffMillisActual));

		}
		catch (Exception e) {
			new Death(e);
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
		new Info("Version: %s", GerkeLib.getOpt("version"));
		new Info("WPM, tentative: %f", GerkeLib.getDoubleOpt("wpm"));
		new Info("frequency: %d", GerkeLib.getIntOpt("freq"));
		new Info("f0,f1: %s", GerkeLib.getOpt("frange"));
		new Info("offest: %d", GerkeLib.getIntOpt("offset"));
		new Info("length: %d", GerkeLib.getIntOpt("length"));
		new Info("clipLevel: %d", GerkeLib.getIntOpt("clip"));
		new Info("level: %f", GerkeLib.getDoubleOpt("level"));
		new Info("detection parameters: %s", GerkeLib.getOpt("detpar"));
		new Info("plot: %b", GerkeLib.getFlag("plot"));
		new Info("verbose: %d", GerkeLib.getIntOpt("verbose"));
		
		for (int k = 0; k < GerkeLib.nofArguments(); k++) {
			new Info("argument %d: %s", k+1, GerkeLib.getArgument(k));
		}
	}

	private static void doGnuplot(String tempFileName) throws IOException, InterruptedException {
		final ProcessBuilder pb =
				new ProcessBuilder(
						"gnuplot-X11",           // very platform specific, TODO
						"--persist",
						"-e",
						"set term x11 size 1400 200",
						"-e",
						String.format("plot '%s' using 1:2 with lines, '%s' using 1:3 with lines",
								tempFileName,
								tempFileName)
						);
		pb.inheritIO();
		final Process pr = pb.start();
		final int exitCode = pr.waitFor();
		new Debug("gnuplot exited with code: %d", exitCode);
	}

	private static String toWindows(String tempFileName) throws IOException {
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

	private static String makeTempFile() throws IOException {
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

	private static double localAmpByHist(int q, double[] sig) {

		final int width = 400;              // PARAMETER
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
		
		final double[] sines = new double[framesPerSlice];
		final double[] coses = new double[framesPerSlice];
		for (int j = 0; j < framesPerSlice; j++) {
			sines[j] = Math.sin(2*Math.PI*f*j/frameRate);
			coses[j] = Math.cos(2*Math.PI*f*j/frameRate);
		}
		
		double rSquaredSum = 0.0;
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
				sinAcc += sines[j]*amp;
				cosAcc += coses[j]*amp;
			}
			final double rSquared = sinAcc*sinAcc + cosAcc*cosAcc;
			rSquaredSum += rSquared;
		}
	}

	private static int getClipLevel(int f, int frameRate, int framesPerSlice) {
		
		final double clipStrength = 0.02;
		
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
			
			if ((1 - clipStrength)*uNoClip > uNew && uNew > (1 - 2*clipStrength)*uNoClip) {
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

		final double[] sines = new double[framesPerSlice];
		final double[] coses = new double[framesPerSlice];
		for (int j = 0; j < framesPerSlice; j++) {
			final double angle = 2*Math.PI*f*j/frameRate; 
			sines[j] = Math.sin(angle);
			coses[j] = Math.cos(angle);
		}
		
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
				sinAcc += sines[j]*amp;
				cosAcc += coses[j]*amp;
			}

			// this quantity is proportional to signal amplitude in time slice
			final double r = Math.sqrt(sinAcc*sinAcc + cosAcc*cosAcc)/framesPerSlice;
			
			rSum += framesPerSlice*r;
			divisor += framesPerSlice;
		}

		return rSum/divisor;
	}
}
