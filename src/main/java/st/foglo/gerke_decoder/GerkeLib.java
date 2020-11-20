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


import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.StringTokenizer;


/**
 * Static methods and classes for command line handling.
 */
public final class GerkeLib {

	public static Map<String, String> params = new HashMap<String, String>();
	public static Map<String, String> defaults = new HashMap<String, String>();
	public static Map<String, Option> opts = new HashMap<String, Option>();
	public static List<String> args = new ArrayList<String>();
	
	public abstract static class Option {
		
		final String shortName;
		final String name;
		final String defaultValue;
		
		public Option(String shortName, String name, String defaultValue) {
			this.shortName = shortName;
			this.name = name;
			this.defaultValue = defaultValue;
			opts.put(shortName, this);
			defaults.put(name, defaultValue);
		}

		/**
		 * The index k points to the argument entity being processed. The
		 * value returned should be set for continued processing.
		 * 
		 * @param k
		 * @param cliArgs
		 * @return
		 */
		abstract int action(int k, String[] cliArgs);
	}
	
	public static class Flag extends Option {
		
		public Flag(String shortName, String name) {
			super(shortName, name, "false");
		}
		
		int action(int k, String[] cliArgs) {
			params.put(this.name, "true");
			if (cliArgs[k].length() > 2) {
				cliArgs[k] = "-" + cliArgs[k].substring(2);
				return k;
			}
			else {
				return k+1;
			}
		}
	}
	
	public static class SteppingOption extends Option {
		
		public SteppingOption(String shortName, String name) {
			super(shortName, name, "0");
		}
		
		int action(int k, String[] cliArgs) {
			final String value = params.get(this.name);
			if (value == null) {
				params.put(this.name, "1");
			}
			else {
				params.put(this.name, String.format("%d", Integer.parseInt(value)+1));
			}
			
			if (cliArgs[k].length() > 2) {
				cliArgs[k] = "-" + cliArgs[k].substring(2);
				return k;
			}
			else {
				return k+1;
			}
		}
	}
	
	
	public static class SingleValueOption extends Option {
		public SingleValueOption(String shortName, String name, String defaultValue) {
			super(shortName, name, defaultValue);
		}

		int action(int k, String[] cliArgs) {
			if (cliArgs[k].length() > 2) {
				final String value = cliArgs[k].substring(2);
				params.put(this.name, value);
				return k+1;
			}
			else {
				params.put(this.name, cliArgs[k+1]);
				return k+2;
			}
		}
	}

	public static class VersionOption extends Option {

		public VersionOption(String shortName, String name, String slogan) {
			super(shortName, name, slogan);
		}
		
		int action(int k, String[] cliArgs) {
			System.out.println(defaults.get(this.name));
			System.exit(0);
			return 0;
		}
	}
	
	
	public static class HelpOption extends Option {
		
		final String[] text;
		
		HelpOption(String shortName, String[] text) {
			super(shortName, "help", "");
			this.text = text;
		}
		
		/**
		 * Index k refers to an option argument, if applicable
		 * @param k
		 * @return
		 */
		int action(int k, String[] cliArgs) {
			for (String line : text) {
				System.out.println(line);
			}
			System.exit(0);
			return 0;
		}
	}
	
	
	public static class Message {
		public Message(String prefix, String message, boolean output) {
			if (output) {
				System.err.printf("%s: %s%s", prefix, message, System.lineSeparator());
			}
		}
	}

	/**
	 * Call any of the constructors to terminate unsuccessfully.
	 */
	public static class Debug extends Message {
		public Debug(String message) {
			super("DEBUG", message, getIntOpt("verbose") >= 2);
		}

		public Debug(String format, int i, double v) {
			this(String.format(format, i, v));
		}

		public Debug(String format, int i) {
			this(String.format(format, i));
		}
		
		public Debug(String format, int i, int j) {
			this(String.format(format, i, j));
		}

		public Debug(String format, int i, int j, int k) {
			this(String.format(format, i, j, k));
		}
		
		public Debug(String format, int i, int j, double v) {
			this(String.format(format, i, j, v));
		}
		
		public Debug(String format, int i, int j, double v, double w) {
			this(String.format(format, i, j, v, w));
		}
		
		public Debug(String format, int i, int j, double v, double w, double x) {
			this(String.format(format, i, j, v, w, x));
		}

		public Debug(String format, long j, long k) {
			this(String.format(format, j, k));
		}

		public Debug(String format, double x) {
			this(String.format(format, x));
		}
	}
	
	
	
	/**
	 * Call any of the constructors to terminate unsuccessfully.
	 */
	public static class Info extends Message {
		public Info(String message) {
			super("INFO", message, getIntOpt("verbose") >= 1);
		}

		public Info(String format, int value) {
			this(String.format(format, value));
		}
		
		public Info(String format, String value) {
			this(String.format(format, value));
		}
		
		public Info(String format, boolean value) {
			this(String.format(format, value));
		}

		public Info(String format, int v1, int v2) {
			this(String.format(format, v1, v2));
		}
		
		public Info(String format, int v, String s) {
			this(String.format(format, v, s));
		}

		public Info(String format, long value) {
			this(String.format(format, value));
		}

		public Info(String format, long v1, long v2) {
			this(String.format(format, v1, v2));
		}

		public Info(String format, double value) {
			this(String.format(format, value));
		}

		public Info(String format, int i, int j, double v, double w) {
			this(String.format(format, i, j, v, w));
		}

		public Info(String format, double x, double y, double z) {
			this(String.format(format, x, y, z));
		}
		
		public Info(String format, int i, int j, int k) {
			this(String.format(format, i, j, k));
		}

	}

	
	/**
	 * Call any of the constructors to terminate unsuccessfully.
	 */
	public static class Warning extends Message {
		public Warning(String message) {
			super("WARNING", message, true);
		}

		public Warning(String format, double x) {
			this(String.format(format, x));
		}
	}
	
	
	/**
	 * Call any of the constructors to terminate unsuccessfully.
	 */
	public static class Death extends Message {
		Death(String message) {
			super("FATAL", message, true);
			System.exit(1);
		}

		public Death(String format, String value) {
			this(String.format(format, value));
		} 
		
		public Death(String format, int value) {
			this(String.format(format, value));
		}

		public Death(String format, int v1, int v2) {
			this(String.format(format, v1, v2));
		}

		public Death(String format, long value) {
			this(String.format(format, value));
		}

		public Death(String format, double value) {
			this(String.format(format, value));
		}

		public Death(String format, String slogan, StackTraceElement[] stackTrace) {
			this(String.format(format, slogan, stackTraceAsString(stackTrace)));
		}
		
		public Death(Exception e) {
			this(String.format("exception: %s%s%s",
					e.toString(),
					System.lineSeparator(),
					stackTraceAsString(e.getStackTrace())));
		}
		
		public static String stackTraceAsString(StackTraceElement[] stackTrace) {
			final StringBuilder sb = new StringBuilder();
			for (int k = 0; k < stackTrace.length; k++) {
				sb.append(stackTrace[k].toString());
				sb.append(System.lineSeparator());
			}
			return sb.toString();
		}
	}



	private GerkeLib() {
	}



	public static void loadArgs(String[] clArgs) {
		for (int i = 0; i < clArgs.length; i += 2) {
			params.put(clArgs[i], clArgs[i+1]);
		}
	}
	
	public static String getOpt(String key) {
		final String value = params.get(key);
		final String result = value != null ? value : defaults.get(key);
		return result;
	}
	
	public static boolean getFlag(String key) {
		return getOpt(key).equals("true");
	}
	
	public static int getIntOpt(String key) {
		return Integer.parseInt(getOpt(key));
	}
	
	public static int[] getIntOptMulti(String key) {
		final String multiValue = getOpt(key);
		final StringTokenizer st = new StringTokenizer(multiValue, ",");
		final int[] result = new int[st.countTokens()];
		for (int k = 0; k < result.length; k++) {
			try {
				result[k] = Integer.parseInt(st.nextToken());
			}
			catch (NumberFormatException e) {
				result[k] = 0;
			}
		}
		return result;
	}
	
	public static double[] getDoubleOptMulti(String key) {
		final String multiValue = getOpt(key);
		final StringTokenizer st = new StringTokenizer(multiValue, ",");
		final double[] result = new double[st.countTokens()];
		for (int k = 0; k < result.length; k++) {
			result[k] = Double.parseDouble(st.nextToken());
		}
		return result;
	}
	
	public static int getOptMultiLength(String key) {
		final String multiValue = getOpt(key);
		final StringTokenizer st = new StringTokenizer(multiValue, ",");
		return st.countTokens();
	}
	
	public static double getDoubleOpt(String key) {
		return Double.parseDouble(getOpt(key));
	}
	
	public static String getDefault(String key) {
		return defaults.get(key);
	}

	public static void die(String message) {
		System.out.println(message);
		System.exit(1);
	}
	
	public static void parseArgs(String[] cliArgs) {
		
		boolean parsingOptions = true;
		for (int k = 0; k < cliArgs.length; ) {
			
			if (cliArgs[k].equals("--")) {
				parsingOptions = false;
				k++;
			}
			else if (parsingOptions && cliArgs[k].startsWith("-") && cliArgs[k].length() > 1) {
				final String name = cliArgs[k].substring(1,2);
				final Option o = opts.get(name);
				if (o == null) {
					throw new RuntimeException(
					String.format("unknown option: %s", name));
				}
				k = o.action(k, cliArgs);
			}
			else {
				args.add(cliArgs[k]);
				k++;
			}
		}
	}

	public static String getArgument(int i) {
		return args.get(i);
	}

	public static int nofArguments() {
		return args.size();
	}
}
