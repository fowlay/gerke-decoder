package st.foglo.gerke_decoder;

import uk.me.berndporr.iirj.ChebyshevI;

public final class LowpassChebyshevI implements LowpassFilter {

	
	private final ChebyshevI filter;
	
	
	
	
	public LowpassChebyshevI(int order, double sampleRate, double cutoffFrequency, double rippleDb) {
		filter = new ChebyshevI();
		filter.lowPass(order, sampleRate, cutoffFrequency, rippleDb);
	}

	@Override
	public double filter(double in) {
		return filter.filter(in);
	}

	@Override
	public void reset() {
	}

}
