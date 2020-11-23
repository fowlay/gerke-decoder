package st.foglo.gerke_decoder;

public interface LowpassFilter {

	double filter(double in);
	
	void reset();
}
