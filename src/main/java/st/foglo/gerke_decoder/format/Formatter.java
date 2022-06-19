package st.foglo.gerke_decoder.format;

import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public final class Formatter {
    StringBuilder sb = new StringBuilder();
    int pos = 0;
    final MessageDigest md;
    final byte[] sp = new byte[]{' '};

    public Formatter() throws NoSuchAlgorithmException {
        md = MessageDigest.getInstance("MD5");
    }

    private static final int lineLength = 72;

    /**
     * @param wordBreak
     * @param text
     * @param timestamp        -1 for no timestamp
     */
    public void add(boolean wordBreak, String text, int timestamp) {

        //new Info("word break: %b, text: '%s'", wordBreak, text);

        sb.append(text);

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
