package st.foglo.gerke_decoder.format;

import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import st.foglo.gerke_decoder.GerkeDecoder;
import st.foglo.gerke_decoder.GerkeLib;

public final class Formatter {

    private enum CapState {LOWER, UPPER};

    StringBuilder sb = new StringBuilder();
    int pos = 0;
    final MessageDigest md;
    final byte[] sp = new byte[]{' '};
    private final String caseMode;
    private final int lineLength;
    private CapState capState = CapState.LOWER;

    public Formatter() throws NoSuchAlgorithmException {
        md = MessageDigest.getInstance("MD5");

        final String[] optValues = GerkeLib.getStringOptMulti(GerkeDecoder.O_TEXT_FORMAT);

        if (optValues.length > 2) {
                caseMode = "";
                lineLength = 0;
                new GerkeLib.Death("Too many option values for option -%s",
                                GerkeLib.getOptShortName(GerkeDecoder.O_TEXT_FORMAT));

        }
        else {
                caseMode = optValues[0];

                if (!(caseMode.equals("L") || caseMode.equals("U") || caseMode.equals("C"))) {
                        new GerkeLib.Death("Expecting option value L, U or C for option -%s",
                                        GerkeLib.getOptShortName(GerkeDecoder.O_TEXT_FORMAT));
                }

                final String lineLengthAsString =
                                optValues.length == 2 ? optValues[1] :
                                        GerkeDecoder.LINE_LENGTH_DEFAULT;

                lineLength =
                                GerkeLib.parseInt(lineLengthAsString);
                if (lineLength == Integer.MIN_VALUE) {
                        new GerkeLib.Death("Expecting numeric value for line length");
                }
                else if (lineLength < 1) {
                        new GerkeLib.Death("Value for line length is out of range");
                }
        }
    }

    /**
     * @param wordBreak
     * @param text
     * @param timestamp        -1 for no timestamp
     */
    public void add(boolean wordBreak, String text, int timestamp) {

            if (caseMode.equals("U")) {
                    sb.append(text.toUpperCase());
            }
            else if (caseMode.equals("C") && capState == CapState.LOWER) {
                    if (wordBreak && (text.equals(".") || text.equals(":") || text.equals("="))) {
                            capState = CapState.UPPER;
                    }
                    sb.append(text);
            }
            else if (caseMode.equals("C") &&
                            capState == CapState.UPPER &&
                            Character.isLetter(text.charAt(0))) {
                    capState = CapState.LOWER;
                    sb.append(text.toUpperCase());
            }
            else {
                    sb.append(text);
            }

        if (wordBreak) {
            md.update(sp);
            md.update(sb.toString().getBytes(Charset.forName("UTF-8")));
        }

        if (timestamp != -1) {
            sb.append(String.format(" /%d/", timestamp));
        }

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
            sb = new StringBuilder();
        }
    }

    public int getPos() {
        return pos;
    }

    public void newLine() {
        System.out.println();
        pos = 0;
    }

    public String getDigest() {
        final byte[] by = md.digest();
        final StringBuilder b = new StringBuilder();
        for (int i = 0; i < by.length; i++) {
            b.append(String.format("%02x", by[i] < 0 ? by[i]+256 : by[i]));
        }
        return b.toString();
    }

    public void flush() {
        if (sb.length() > 0) {
            add(true, "", -1);
        }
    }
}
