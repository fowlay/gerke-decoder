package st.foglo.gerke_decoder;

import uk.me.berndporr.iirj.Butterworth;

public final class LowpassButterworth implements LowpassFilter {
	
	private final Butterworth filter;
	
	public LowpassButterworth(int order, double sampleRate, double cutoffFrequency, double ignored) {
		filter = new Butterworth();
		filter.lowPass(order, sampleRate, cutoffFrequency);
	}

	@Override
	public double filter(double in) {
		return filter.filter(in);
	}

	@Override
	public void reset() {
	}
}
