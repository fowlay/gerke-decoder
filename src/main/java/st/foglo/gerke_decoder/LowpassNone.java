package st.foglo.gerke_decoder;

public final class LowpassNone implements LowpassFilter {

	@Override
	public double filter(double in) {
		return in;
	}

	@Override
	public void reset() {
	}
}
