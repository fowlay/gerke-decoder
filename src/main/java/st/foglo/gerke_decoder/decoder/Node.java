package st.foglo.gerke_decoder.decoder;

import st.foglo.gerke_decoder.lib.Compute;

public final class Node {
    
    public static final Node tree = new Node("", "");
    
    
    
    

    final String code;
    public final String text;
    Node dash = null;
    Node dot = null;
    public final int nTus;

    public Node(String text, String code) {
        this.code = code;
        Node x = tree;
        int tuCount = 0;
        for (int j = 0; j < code.length() - 1; j++) {

            if (code.charAt(j) == '.') {
                x = x.dot;
                tuCount += 2;
            }
            else if (code.charAt(j) == '-') {
                tuCount += 4;
                x = x.dash;
            }
        }
        if (code.length() > 0) {
            if (code.charAt(code.length() - 1) == '.') {
                if (x.dot != null) {
                    throw new RuntimeException(
                            String.format("duplicate node: %s", code));
                }
                x.dot = this;
                tuCount += 1;
            }
            else if (code.charAt(code.length() - 1) == '-') {
                if (x.dash != null) {
                    throw new RuntimeException(
                            String.format("duplicate node: %s", code));
                }
                x.dash = this;
                tuCount += 3;
            }
        }
        this.text = text == null ? "["+code+"]" : text;
        this.nTus = tuCount;
    }

    public synchronized Node newNode(String code) {
        if (code.equals("-")) {
            if (this.dash == null) {
                final String newCode = this.code + code;
                this.dash = new Node("[" + newCode + "]", newCode);
                return this.dash;
            }
            else {
                return this.dash;
            }
        }
        else if (code.equals(".")) {
            if (this.dot == null) {
                final String newCode = this.code + code;
                this.dot = new Node("[" + newCode + "]", newCode);
                return this.dot;
            }
            else {
                return this.dot;
            }
        }
        else {
            throw new IllegalArgumentException();
        }
    }
    


    static {
        new Node("e", ".");
        new Node("t", "-");

        new Node("i", "..");
        new Node("a", ".-");
        new Node("n", "-.");
        new Node("m", "--");

        new Node("s", "...");
        new Node("u", "..-");
        new Node("r", ".-.");
        new Node("w", ".--");
        new Node("d", "-..");
        new Node("k", "-.-");
        new Node("g", "--.");
        new Node("o", "---");

        new Node("h", "....");
        new Node("v", "...-");
        new Node("f", "..-.");
        new Node(Compute.encodeLetter(252), "..--");
        new Node("l", ".-..");
        new Node(Compute.encodeLetter(228), ".-.-");
        new Node("p", ".--.");
        new Node("j", ".---");
        new Node("b", "-...");
        new Node("x", "-..-");
        new Node("c", "-.-.");
        new Node("y", "-.--");
        new Node("z", "--..");
        new Node("q", "--.-");
        new Node(Compute.encodeLetter(246), "---.");
        new Node("ch", "----");

        new Node(Compute.encodeLetter(233), "..-..");
        new Node(Compute.encodeLetter(229), ".--.-");

        new Node("0", "-----");
        new Node("1", ".----");
        new Node("2", "..---");
        new Node("3", "...--");
        new Node("4", "....-");
        new Node("5", ".....");
        new Node("6", "-....");
        new Node("7", "--...");
        new Node("8", "---..");
        new Node("9", "----.");

        new Node("/", "-..-.");

        new Node("+", ".-.-.");

        new Node(".", ".-.-.-");

        new Node(null, "--..-");
        new Node(",", "--..--");

        new Node("=", "-...-");

        new Node("-", "-....-");

        new Node(":", "---...");
        new Node(null, "-.-.-");
        new Node(";", "-.-.-.");

        new Node("(", "-.--.");
        new Node(")", "-.--.-");

        new Node("'", ".----.");

        new Node(null, "..--.");
        new Node("?", "..--..");

        new Node(null, ".-..-");
        new Node("\"", ".-..-.");

        new Node("<AS>", ".-...");

        new Node(null, "-...-.");
        new Node("<BK>", "-...-.-");

        new Node(null, "...-.");
        new Node("<SK>", "...-.-");
        new Node(null, "...-..");
        new Node("$", "...-..-");

        new Node(null, "-.-..");
        new Node(null, "-.-..-");
        new Node(null, "-.-..-.");
        new Node("<CL>", "-.-..-..");

        new Node(null, "...---");
        new Node(null, "...---.");
        new Node(null, "...---..");
        new Node("<SOS>", "...---...");
    }



}
