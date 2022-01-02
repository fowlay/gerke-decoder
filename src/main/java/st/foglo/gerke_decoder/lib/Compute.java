package st.foglo.gerke_decoder.lib;

public final class Compute {

	private Compute() {
	}
	
    public static double dMax(double a, double b) {
        return a > b ? a : b;
    }

    public static double dMin(double a, double b) {
        return a < b ? a : b;
    }

    public static int iMax(int a, int b) {
        return a > b ? a : b;
    }

    public static int iMin(int a, int b) {
        return a < b ? a : b;
    }

    public static int roundToOdd(double x) {
        final int k = (int) Math.round(x);
        return k % 2 == 0 ? k+1 : k;
    }

    public static int ensureEven(int k) {
        return k % 2 == 1 ? k+1 : k;
    }
    
    public static String encodeLetter(int i) {
        return new String(new int[]{i}, 0, 1);
    }
    
    public static double squared(double x) {
        return x*x;
    }
}
