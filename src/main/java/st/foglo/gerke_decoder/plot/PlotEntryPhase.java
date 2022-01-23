package st.foglo.gerke_decoder.plot;

public final class PlotEntryPhase extends PlotEntryBase {
	
	public final double phase;
	public final double strength;

	protected PlotEntryPhase(double phase, double strength) {
		this.phase = phase;
		this.strength = strength;
	}
}
