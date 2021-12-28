package codechicken.diffpatch.util;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Converts Equal lines into equal single characters
 * and Equal single words into equal single characters.
 */
public class CharRepresenter {

    private final List<String> charToLine = new ArrayList<>();
    private final Map lineToChar = new HashMap<String, Character>();

    private final List<String> charToWord = new ArrayList<>();
    private final Map wordToChar = new HashMap<String, Character>();

    public CharRepresenter() {
        charToLine.add("\0");//lets avoid the 0 char

        //keep ascii chars as their own values
        for (char i = 0; i < 0x80; i++) {
            charToWord.add(Character.valueOf(i).toString());
        }
    }

    public String getWordForChar(char ch) {
        return charToWord.get(ch);
    }

    public char addLine(String line) {
        return (char)lineToChar.computeIfAbsent(line, e -> {
            charToLine.add(line);
            return (char) (charToLine.size() - 1);
        });
    }

    public char addWord(String word) {
        if (word.length() == 1 && word.charAt(0) <= 0x80) {
            return word.charAt(0);
        }

        return (char)wordToChar.computeIfAbsent(word, e -> {
            charToWord.add(word);
            return (char) (charToWord.size() - 1);
        });
    }

    private char[] buf = new char[4096];

    public String wordsToChars(String line) {
        int b = 0;

        for (int i = 0, len; i < line.length(); i += len) {
            char c = line.charAt(i);
            //identify word
            len = 1;
            if (Character.isLetter(c)) {
                while (i + len < line.length() && Character.isLetterOrDigit(line.charAt(i + len))) {
                    len++;
                }
            } else if (Character.isDigit(c)) {
                while (i + len < line.length() && Character.isDigit(line.charAt(i + len))) {
                    len++;
                }
            } else if (c == ' ' || c == '\t') {
                while (i + len < line.length() && line.charAt(i + len) == c) {
                    len++;
                }
            }
            String word = line.substring(i, i + len);
            if (b > buf.length) {
                buf = Arrays.copyOf(buf, buf.length * 2);
            }
            buf[b++] = addWord(word);
        }
        return new String(buf, 0, b);
    }

    public String linesToChars(List<String> lines) {
        char[] buf = new char[lines.size()];
        for (int i = 0; i < lines.size(); i++) {
            buf[i] = addLine(lines.get(i));
        }
        return new String(buf);
    }

    public int getMaxLineChar() {
        return charToLine.size();
    }

    public int getMaxWordChar() {
        return charToWord.size();
    }

}