package st.foglo.gerke_decoder.detector.cw_basic;

import java.util.concurrent.CountDownLatch;

import st.foglo.gerke_decoder.LowpassFilter;

public abstract class FilterRunnerBase implements Runnable {

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

	public FilterRunnerBase(LowpassFilter f, short[] wav, double[] out, int framesPerSlice, int clipLevel, int freq,
			int frameRate, double phaseShift, CountDownLatch cdl, double tsLength) {
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
