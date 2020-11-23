package st.foglo.gerke_decoder;

import st.foglo.gerke_decoder.GerkeLib.*;

public final class LowpassWindow implements LowpassFilter {
	
	private final double[] window;
	private int index;
	private final int windowSize;
	double arraySum;
	
	public LowpassWindow(int frameRate, double cutoffFrequency) {
		windowSize = (int) Math.round(frameRate/cutoffFrequency);
		new Debug("LowpassWindow: size: %d", windowSize);
		window = new double[windowSize];
		for (int i = 0; i < windowSize; i++) {
			window[i] = 0.0;
		}
		index = 0;
		arraySum = 0.0;
	}

	@Override
	public double filter(double in) {
		
		double oldValue = window[index];
		window[index] = in;
		arraySum += (in - oldValue);
		
		index++;
		if (index == windowSize) {
			index = 0;
		}

		return arraySum/windowSize;
	}

	@Override
	public void reset() {
		arraySum = 0.0;
		for (int i = 0; i < windowSize; i++) {
			arraySum += window[i];
		}
	}
}
