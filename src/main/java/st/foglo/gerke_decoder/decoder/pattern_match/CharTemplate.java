package st.foglo.gerke_decoder.decoder.pattern_match;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import st.foglo.gerke_decoder.GerkeLib.Death;
import st.foglo.gerke_decoder.lib.Compute;

public final class CharTemplate {
	
	public static Map<Integer, List<CharTemplate>> templs =
			new TreeMap<Integer, List<CharTemplate>>();
	
    public static final int HI = 10;
    public static final int LO = -35;
    
    static {
        new CharTemplate("a", ".-");
        new CharTemplate("b", "-...");
        new CharTemplate("c", "-.-.");
        new CharTemplate("d", "-..");
        new CharTemplate("e", ".");
        new CharTemplate("f", "..-.");
        new CharTemplate("g", "--.");
        new CharTemplate("h", "....");
        new CharTemplate("i", "..");
        new CharTemplate("j", ".---");
        new CharTemplate("k", "-.-");
        new CharTemplate("l", ".-..");
        new CharTemplate("m", "--");
        new CharTemplate("n", "-.");
        new CharTemplate("o", "---");
        new CharTemplate("p", ".--.");
        new CharTemplate("q", "--.-");
        new CharTemplate("r", ".-.");
        new CharTemplate("s", "...");
        new CharTemplate("t", "-");
        new CharTemplate("u", "..-");
        new CharTemplate("v", "...-");
        new CharTemplate("w", ".--");
        new CharTemplate("x", "-..-");
        new CharTemplate("y", "-.--");
        new CharTemplate("z", "--..");

        new CharTemplate("1", ".----");
        new CharTemplate("2", "..---");
        new CharTemplate("3", "...--");
        new CharTemplate("4", "....-");
        new CharTemplate("5", ".....");
        new CharTemplate("6", "-....");
        new CharTemplate("7", "--...");
        new CharTemplate("8", "---..");
        new CharTemplate("9", "----.");
        new CharTemplate("0", "-----");

        new CharTemplate(Compute.encodeLetter(252), "..--");
        new CharTemplate(Compute.encodeLetter(228), ".-.-");
        new CharTemplate(Compute.encodeLetter(246), "---.");
        new CharTemplate("ch", "----");
        new CharTemplate(Compute.encodeLetter(233), "..-..");

        new CharTemplate(Compute.encodeLetter(229), ".--.-");

        new CharTemplate("/", "-..-.");
        new CharTemplate("+", ".-.-.");
        new CharTemplate(".", ".-.-.-");
        new CharTemplate(null, "--..-");
        new CharTemplate(",", "--..--");
        new CharTemplate("=", "-...-");
        new CharTemplate("-", "-....-");
        new CharTemplate(null, ".-....-");
        new CharTemplate(":", "---...");
        new CharTemplate(null, "-.-.-");
        new CharTemplate(";", "-.-.-.");
        new CharTemplate("(", "-.--.");
        new CharTemplate(")", "-.--.-");
        new CharTemplate("'", ".----.");
        new CharTemplate(null, "..--.");
        new CharTemplate("?", "..--..");
        new CharTemplate(null, ".-..-");
        new CharTemplate("\"", ".-..-.");
        new CharTemplate("<AS>", ".-...");
        new CharTemplate(null, "-...-.");
        new CharTemplate("<BK>", "-...-.-");
        new CharTemplate(null, "...-.");
        new CharTemplate("<SK>", "...-.-");
        new CharTemplate(null, "...-..");
        new CharTemplate("$", "...-..-");
        new CharTemplate(null, "-.-..");
        new CharTemplate(null, "-.-..-");
        new CharTemplate(null, "-.-..-.");
        new CharTemplate("<CL>", "-.-..-..");
        new CharTemplate(null, "...---");
        new CharTemplate(null, "...---.");
        new CharTemplate(null, "...---..");
        new CharTemplate("<SOS>", "...---...");
    }




    public final int[] pattern;
    public final String text;

    public CharTemplate(String text, String code) {

        this.text = text == null ? "["+code+"]" : text;

        int size = 0;
        int count = 0;
        for (char x : code.toCharArray()) {
            count++;
            if (x == '.') {
                size++;
            }
            else if (x == '-') {
                size += 3;
            }
        }
        size += (count-1);
        final Integer sizeKey = Integer.valueOf(size);

        pattern = new int[size];
        for (int k = 0; k < pattern.length; k++) {
            pattern[k] = LO;
        }

        int index = 0;
        for (char x : code.toCharArray()) {
            if (x == '.') {
                pattern[index] = HI;
                index += 2;
            }
            else if (x == '-') {
                pattern[index] = HI;
                pattern[index+1] = HI;
                pattern[index+2] = HI;
                index += 4;
            }
        }

        for (int i = 0; i < pattern.length; i++) {
            if (pattern[i] != LO && pattern[i] != HI) {
                new Death("bad CharTemplate");  // impossible
            }
        }

        final List<CharTemplate> existing = templs.get(sizeKey);
        if (existing == null) {
            final List<CharTemplate> list = new ArrayList<CharTemplate>();
            list.add(this);
            templs.put(sizeKey, list);
        }
        else {
            existing.add(this);
        }
    }
    


}
