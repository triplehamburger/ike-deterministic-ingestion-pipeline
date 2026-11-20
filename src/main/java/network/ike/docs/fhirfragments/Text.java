package network.ike.docs.fhirfragments;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * String helpers whose behaviour matches the reference implementation exactly: whitespace splitting, code-point
 * lengths, the attribute wrapper, table-cell escaping and the small Markdown to AsciiDoc conversion.
 */
final class Text {
    /** Wrap width of the {@code :topic-*:} attribute lines (same as TopicFragmentWriter). */
    static final int WRAP = 92;
    static final String BS = "\\";
    static final String NL = "\n";
    /** Private marker for a hard line break inside a table cell (one URL per line). */
    static final char LINE_BREAK = '';
    static final String NOT_SPEC = "Not specified in source";

    private static final int FLAGS = Pattern.UNIX_LINES | Pattern.UNICODE_CHARACTER_CLASS;

    private Text() {
    }

    /** Compile with the flags every pattern here needs: only {@code \n} ends a line, {@code \w \s \d \b} are Unicode. */
    static Pattern re(String regex) {
        return Pattern.compile(regex, FLAGS);
    }

    static Pattern re(String regex, int extraFlags) {
        return Pattern.compile(regex, FLAGS | extraFlags);
    }

    /** True for the characters that Python's {@code str.isspace()} accepts. */
    static boolean isSpace(int c) {
        return (c >= 0x09 && c <= 0x0d) || (c >= 0x1c && c <= 0x20) || c == 0x85 || c == 0xa0 || c == 0x1680
                || (c >= 0x2000 && c <= 0x200a) || c == 0x2028 || c == 0x2029 || c == 0x202f || c == 0x205f
                || c == 0x3000;
    }

    /** Python {@code s.split()}: runs of whitespace separate tokens, no empty tokens. */
    static List<String> splitWords(String s) {
        List<String> out = new ArrayList<>();
        int start = -1;
        int i = 0;
        while (i < s.length()) {
            int cp = s.codePointAt(i);
            int n = Character.charCount(cp);
            if (isSpace(cp)) {
                if (start >= 0) {
                    out.add(s.substring(start, i));
                    start = -1;
                }
            } else if (start < 0) {
                start = i;
            }
            i += n;
        }
        if (start >= 0) {
            out.add(s.substring(start));
        }
        return out;
    }

    /** {@code ' '.join(s.split())}: whitespace runs collapse to one space, ends trimmed. */
    static String normalizeSpace(String s) {
        return String.join(" ", splitWords(s));
    }

    static String strip(String s) {
        return rstrip(lstrip(s));
    }

    static String lstrip(String s) {
        int i = 0;
        while (i < s.length() && isSpace(s.codePointAt(i))) {
            i += Character.charCount(s.codePointAt(i));
        }
        return s.substring(i);
    }

    static String rstrip(String s) {
        int end = s.length();
        while (end > 0) {
            int cp = s.codePointBefore(end);
            if (!isSpace(cp)) {
                break;
            }
            end -= Character.charCount(cp);
        }
        return s.substring(0, end);
    }

    /** Remove the given character from both ends. */
    static String stripChar(String s, char c) {
        int a = 0;
        int b = s.length();
        while (a < b && s.charAt(a) == c) {
            a++;
        }
        while (b > a && s.charAt(b - 1) == c) {
            b--;
        }
        return s.substring(a, b);
    }

    /** Remove leading occurrences of the given character. */
    static String lstripChar(String s, char c) {
        int a = 0;
        while (a < s.length() && s.charAt(a) == c) {
            a++;
        }
        return s.substring(a);
    }

    /** Remove trailing occurrences of any of the given characters. */
    static String rstripChars(String s, String chars) {
        int end = s.length();
        while (end > 0 && chars.indexOf(s.charAt(end - 1)) >= 0) {
            end--;
        }
        return s.substring(0, end);
    }

    /** Length in code points (Python {@code len}). */
    static int len(String s) {
        return s.codePointCount(0, s.length());
    }

    /** First {@code n} code points. */
    static String head(String s, int n) {
        if (len(s) <= n) {
            return s;
        }
        return s.substring(0, s.offsetByCodePoints(0, n));
    }

    /** Split on {@code \n}, keeping a trailing empty element (Python {@code s.split('\n')}). */
    static List<String> lines(String text) {
        return new ArrayList<>(Arrays.asList(text.split("\n", -1)));
    }

    /** Replace only the first occurrence of a literal (Python {@code s.replace(a, b, 1)}). */
    static String replaceOnce(String s, String a, String b) {
        int i = s.indexOf(a);
        return i < 0 ? s : s.substring(0, i) + b + s.substring(i + a.length());
    }

    /** Split before every match of a zero-width pattern; a match at position 0 yields a leading empty part. */
    static List<String> splitBefore(Pattern zeroWidth, String s) {
        List<String> parts = new ArrayList<>();
        Matcher m = zeroWidth.matcher(s);
        int last = 0;
        boolean any = false;
        while (m.find()) {
            any = true;
            parts.add(s.substring(last, m.start()));
            last = m.start();
        }
        parts.add(s.substring(last));
        return any ? parts : new ArrayList<>(List.of(s));
    }

    /** Port of TopicFragmentWriter.attributeLine: wrap at {@link #WRAP} with a trailing backslash. */
    static String attributeLine(String name, String value) {
        value = normalizeSpace(value == null ? "" : value);
        if (value.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        String line = ":" + name + ":";
        for (String word : value.split(" ")) {
            if (len(line) + 1 + len(word) > WRAP && !strip(line).isEmpty() && !line.endsWith(":")) {
                out.append(line).append(' ').append(BS).append('\n');
                line = " ";
            }
            line += " " + word;
        }
        out.append(line).append('\n');
        return out.toString();
    }

    /**
     * Table cell text: pipes escaped, whitespace collapsed to one line, except at {@link #LINE_BREAK}, which
     * becomes an AsciiDoc hard line break (' +' then a new line).
     */
    static String cell(String v) {
        if (v == null) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        for (String p : v.split(String.valueOf(LINE_BREAK), -1)) {
            parts.add(normalizeSpace(p).replace("|", BS + "|"));
        }
        return String.join(" +\n", parts);
    }

    private static final Pattern UNICODE_ESCAPE = re("\\\\u([0-9a-fA-F]{4})");
    private static final Pattern MD_LINK = re("\\[([^\\]]+)\\]\\(([^)\\s]+)\\)");
    private static final Pattern MD_BOLD = re("\\*\\*(.+?)\\*\\*", Pattern.DOTALL);
    private static final Pattern MD_ITALIC = re("(?<![\\*\\w])\\*(?![\\s\\*])(.+?)(?<![\\s\\*])\\*(?![\\*\\w])");
    private static final Pattern MD_BULLET = re("^(\\s*)-\\s+", Pattern.MULTILINE);
    private static final Pattern HTTP = re("^https?://");

    /** Deterministic Markdown to AsciiDoc subset: links, bold, italics, bullets (see render-spec section 3). */
    static String mdToAdoc(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String t = text.replace("\r\n", "\n").replace("\r", "\n");
        t = UNICODE_ESCAPE.matcher(t).replaceAll(
                mr -> Matcher.quoteReplacement(new String(Character.toChars(Integer.parseInt(mr.group(1), 16)))));
        t = MD_LINK.matcher(t).replaceAll(mr -> {
            String label = mr.group(1);
            String url = strip(mr.group(2));
            return Matcher.quoteReplacement(HTTP.matcher(url).find() ? url + "[" + label + "]" : label);
        });
        t = MD_BOLD.matcher(t).replaceAll("\u0001$1\u0002");
        t = MD_ITALIC.matcher(t).replaceAll("_$1_");
        t = t.replace('\u0001', '*').replace('\u0002', '*');
        t = MD_BULLET.matcher(t).replaceAll("$1* ");
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (String l : t.split("\n", -1)) {
            if (!first) {
                sb.append('\n');
            }
            first = false;
            sb.append(rstrip(l));
        }
        return stripChar(sb.toString(), '\n');
    }
}
