import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Lists every Spanish text of the interface (frontend JavaScript and the backend's messages) in the same form the
 * app's translator asks for it: whitespace collapsed and numbers or variable values as {0}, {1}...
 * The list is src/main/resources/i18n/ui-texts.txt of this AI service, which translates it in advance (UiTextWarmer).
 * It reads the four repositories: the frontend, the backend and this service (their messages) and the database
 * (the system profiles). It needs nothing but Java 21:
 *   java scripts/ExtractUiTexts.java ../FrontendPatSft ../BackendPatSft . ../DBPatSft src/main/resources/i18n/ui-texts.txt
 * The frontend modules are read with a small JavaScript lexer that finds the string and template literals.
 */
public class ExtractUiTexts {

    // JavaScript's \s, which also covers the Unicode spaces (no-break space and others).
    static final String WS = "[\\t\\n\\x0B\\f\\r \\u00a0\\u1680\\u2000-\\u200a\\u2028\\u2029\\u202f\\u205f\\u3000\\ufeff]";
    static final Pattern WS_RUN = Pattern.compile(WS + "+");
    static final Pattern WS_ONE = Pattern.compile(WS);
    static final Pattern HTML_EXPR = Pattern.compile("^(icon|aquaFace|loading|emptyState|helpBox|inputField|relativeTime|avatarMarkup|logo|calculatorCard|reportView|chatCitations|withReferenceMarks|passwordChecklist|own)\\(|\\.map\\(|\\.join\\(|Markup|Html|html|svg|SVG|chart|Chart|card|Card|badge|rows|list|items|options|body|content|markup");
    static final Map<String, String> ENT = Map.of("&amp;", "&", "&lt;", "<", "&gt;", ">", "&quot;", "\"", "&#39;", "'", "&nbsp;", " ");
    static final Pattern LETTER = Pattern.compile("\\p{L}");
    static final Pattern SPANISH_HINT = Pattern.compile("[áéíóúñ¿¡ÁÉÍÓÚÑ]|\\b(el|la|los|las|de|del|que|para|con|una|un|sin|por|tu|tus|se|es|en|y|o|al|no|más|tiene|puedes|está)\\b",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    static final Pattern KEY_VALUE = Pattern.compile("\\u0000|\\d+(?:[.,:/]\\d+)*");
    static final Pattern MARKUP = Pattern.compile("<\\w|</\\w");
    static final Pattern ATTRIBUTE = Pattern.compile("\\b(?:placeholder|title|aria-label|alt|value)=\"([^\"]*)\"");
    static final Pattern ENTITY = Pattern.compile("&[a-z#0-9]+;");

    static final Set<String> texts = new LinkedHashSet<>();

    public static void main(String[] args) throws IOException {
        if (args.length < 5) {
            System.err.println("Uso: java scripts/ExtractUiTexts.java FRONTEND BACKEND IA BASE SALIDA");
            System.exit(1);
        }
        Path front = Path.of(args[0]), back = Path.of(args[1]), ai = Path.of(args[2]), db = Path.of(args[3]), out = Path.of(args[4]);

        // ---------- frontend ----------
        for (Path file : files(front.resolve("js"), ".js")) {
            new Lexer(read(file)).run();
        }
        addFromMarkup(read(front.resolve("index.html"))
                .replaceAll("<script[\\s\\S]*?</script>|<style[\\s\\S]*?</style>|<head>[\\s\\S]*?</head>", ""));
        int frontCount = texts.size();

        // ---------- backend messages (what reaches the screen: errors, labels, advice) ----------
        Pattern skipFile = Pattern.compile("/(intelligence/agent/(ReportAgent|SubmitReportSchema)|intelligence/ai/|i18n/)");
        Pattern skipLine = Pattern.compile("LOG\\.|log\\.(info|warn|error|debug)|@Pattern|@Query|Pattern\\.compile|regexp");
        Pattern quoted = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"");
        Pattern format = Pattern.compile("%[-+0-9.,]*[dfe]");
        Pattern label = Pattern.compile("^[A-ZÁÉÍÓÚ][a-záéíóúñ]+( [a-záéíóúñ]+){0,4}$");
        for (Path repo : List.of(back, ai)) {
            for (Path file : files(repo.resolve("src/main/java"), ".java")) {
                if (skipFile.matcher(file.toString().replace('\\', '/')).find()) continue; // prompts to the AI, not screen text
                for (String line : read(file).split("\n", -1)) {
                    if (skipLine.matcher(line).find()) continue;
                    Matcher m = quoted.matcher(line);
                    while (m.find()) {
                        String s = m.group(1).replace("\\n", " ").replace("\\\"", "\"");
                        if (s.contains("%s")) continue;
                        s = format.matcher(s).replaceAll("\u0000");
                        if (!SPANISH_HINT.matcher(s).find() || !WS_ONE.matcher(trim(s)).find()) {
                            if (!label.matcher(trim(s)).find()) continue;
                        }
                        add(s);
                    }
                }
            }
        }

        // ---------- the server's own data shown on screen: the knowledge base and the system profiles ----------
        int before = texts.size();
        @SuppressWarnings("unchecked")
        Map<String, Object> kb = (Map<String, Object>) new Json(read(ai.resolve("src/main/resources/knowledge/Guidelines.json"))).value();
        for (Object o : (List<?>) kb.get("sources")) {
            Map<?, ?> source = (Map<?, ?>) o;
            for (String field : List.of("organization", "legalStatus", "edition")) addData(source.get(field));
        }
        for (Object o : (List<?>) kb.get("chunks")) addData(((Map<?, ?>) o).get("summaryEs"));
        Pattern sqlString = Pattern.compile("'((?:[^']|'')*)'");
        Pattern profiles = Pattern.compile("(INSERT INTO|UPDATE) water_profiles");
        try (Stream<Path> migrations = Files.list(db.resolve("migrations"))) {
            for (Path f : migrations.toList()) {
                String sql = read(f);
                if (!sql.contains("water_profiles") || !profiles.matcher(sql).find()) continue;
                Matcher m = sqlString.matcher(sql);
                while (m.find()) {
                    String t = m.group(1).replace("''", "'");
                    if (WS_ONE.matcher(trim(t)).find() && SPANISH_HINT.matcher(t).find() && t.length() > 3) addData(t);
                }
            }
        }
        System.out.println("server data: " + (texts.size() - before));
        List<String> list = new ArrayList<>(texts);
        list.sort(null); // UTF-16 order, the same as JavaScript's default sort
        Files.writeString(out, String.join("\n", list) + "\n", StandardCharsets.UTF_8);
        System.out.println("frontend: " + frontCount + ", backend: " + (texts.size() - frontCount) + ", total: " + list.size());
    }

    // The translator's key: whitespace collapsed; variables (\u0000) and numbers become {0}, {1}... from left to right.
    static String key(String raw) {
        String collapsed = trim(WS_RUN.matcher(raw).replaceAll(" "));
        Matcher m = KEY_VALUE.matcher(collapsed);
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (m.find()) m.appendReplacement(sb, Matcher.quoteReplacement("{" + i++ + "}"));
        m.appendTail(sb);
        return sb.toString();
    }

    static String trim(String s) {
        return s.replaceAll("^" + WS + "+|" + WS + "+$", "");
    }

    static final Pattern CODE = Pattern.compile("[;=`\\\\$]|=>|\\(\\)|\\.(js|css|png|svg|json|mjs)\\b|https?:|#/|^--|^[/.@]|\\b\\w+\\(|\\[\\]|&&|\\|\\|");
    static final Pattern PLACEHOLDER = Pattern.compile("\\{\\d+\\}");

    static boolean looksLikeText(String t) {
        if (t.length() < 2 || t.length() > 1500 || !LETTER.matcher(PLACEHOLDER.matcher(t).replaceAll("")).find()) return false;
        if (CODE.matcher(t).find()) return false;
        if (t.matches("[A-Z0-9_]+")) return false;                                          // CONSTANT_KEY
        if (Pattern.compile("[\"<>]|prefers-|@media").matcher(t).find()) return false;      // leftovers of markup and CSS
        if (t.matches("[a-z0-9_.:\\-]+") && !Pattern.compile("[áéíóúñ]").matcher(t).find()) return false; // css-class, identifiers, mime types
        if (t.matches("[a-z]+[A-Z]\\w*")) return false;                                      // camelCase
        if (t.matches("\\{\\d+\\}")) return false;
        String words = trim(PLACEHOLDER.matcher(t).replaceAll(""));
        if (words.isEmpty()) return false;
        // One word: only if it starts with a capital ("Medir") or carries a Spanish accent ("ácida").
        if (!WS_ONE.matcher(words).find()) {
            return words.matches("[A-ZÁÉÍÓÚÑ¿¡][a-záéíóúñü]+[.:!?]?") || Pattern.compile("[áéíóúñ]").matcher(words).find();
        }
        return true;
    }

    static void addFromMarkup(String s) {
        // Attribute values the translator handles.
        Matcher m = ATTRIBUTE.matcher(s);
        while (m.find()) add(m.group(1));
        // Text between tags (each tag is a boundary, as in the page).
        for (String part : s.replaceAll("<[^>]*>", "\n").split("\n", -1)) add(part);
    }

    static void add(String raw) {
        Matcher m = ENTITY.matcher(raw);
        StringBuilder sb = new StringBuilder();
        while (m.find()) m.appendReplacement(sb, Matcher.quoteReplacement(ENT.getOrDefault(m.group(), " ")));
        m.appendTail(sb);
        String t = sb.toString();
        if (t.indexOf('\u0001') >= 0) return;
        String k = key(t);
        if (looksLikeText(k)) texts.add(k);
    }

    static void addData(Object raw) {
        String k = key(raw == null ? "" : String.valueOf(raw));
        if (k.length() >= 2 && k.length() <= 1500 && LETTER.matcher(k).find()) texts.add(k);
    }

    static void addLiteral(String value) {
        if (MARKUP.matcher(value).find()) addFromMarkup(value); else add(value);
    }

    static List<Path> files(Path dir, String extension) throws IOException {
        try (Stream<Path> walk = Files.walk(dir)) {
            return walk.filter(p -> Files.isRegularFile(p) && p.toString().endsWith(extension)).sorted().toList();
        }
    }

    static String read(Path file) throws IOException {
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    /**
     * Finds the string literals and template literals of a JavaScript module, the way the acorn parser reports them:
     * strings with their escapes resolved, and templates with each ${expression} turned into a value (\u0000) or,
     * when the expression produces markup, a tag that cuts the text.
     */
    static final class Lexer {
        static final Set<String> BEFORE_REGEX = Set.of("return", "typeof", "instanceof", "in", "of", "new", "delete", "void",
                "throw", "case", "do", "else", "yield", "await");
        final String src;
        int pos;
        // What came before: true when a "/" there would start a regular expression and not divide.
        boolean regexAllowed = true;

        Lexer(String src) {
            this.src = src;
        }

        void run() {
            scan(src.length(), false);
        }

        /** Scans until the end, or until the "}" that closes a template expression; returns the position of that "}". */
        int scan(int end, boolean untilBrace) {
            int depth = 0;
            while (pos < end) {
                char c = src.charAt(pos);
                if (c == '/' && pos + 1 < end && src.charAt(pos + 1) == '/') {
                    while (pos < end && src.charAt(pos) != '\n') pos++;
                } else if (c == '/' && pos + 1 < end && src.charAt(pos + 1) == '*') {
                    int close = src.indexOf("*/", pos + 2);
                    pos = close < 0 ? end : close + 2;
                } else if (c == '"' || c == '\'') {
                    addLiteral(string(c));
                    regexAllowed = false;
                } else if (c == '`') {
                    template();
                    regexAllowed = false;
                } else if (c == '/') {
                    if (regexAllowed) { regex(); regexAllowed = false; } else { pos++; regexAllowed = true; }
                } else if (Character.isJavaIdentifierStart(c)) {
                    int start = pos;
                    while (pos < end && Character.isJavaIdentifierPart(src.charAt(pos))) pos++;
                    regexAllowed = BEFORE_REGEX.contains(src.substring(start, pos));
                } else if (Character.isDigit(c)) {
                    while (pos < end && (Character.isLetterOrDigit(src.charAt(pos)) || src.charAt(pos) == '.' || src.charAt(pos) == '_')) pos++;
                    regexAllowed = false;
                } else if (c == '{') {
                    depth++;
                    pos++;
                    regexAllowed = true;
                } else if (c == '}') {
                    if (untilBrace && depth == 0) return pos;
                    depth--;
                    pos++;
                    regexAllowed = false;
                } else if (c == ')' || c == ']') {
                    pos++;
                    regexAllowed = false;
                } else if (Character.isWhitespace(c) || c == '\u00a0' || c == '\ufeff') {
                    pos++;
                } else {
                    pos++;
                    regexAllowed = true;
                }
            }
            return pos;
        }

        String string(char quote) {
            pos++;
            StringBuilder sb = new StringBuilder();
            while (pos < src.length()) {
                char c = src.charAt(pos);
                if (c == quote) { pos++; break; }
                if (c == '\\') { escape(sb); continue; }
                sb.append(c);
                pos++;
            }
            return sb.toString();
        }

        void template() {
            pos++;
            StringBuilder s = new StringBuilder();
            while (pos < src.length()) {
                char c = src.charAt(pos);
                if (c == '`') { pos++; break; }
                if (c == '\\') { escape(s); continue; }
                if (c == '$' && pos + 1 < src.length() && src.charAt(pos + 1) == '{') {
                    pos += 2;
                    int start = pos;
                    boolean saved = regexAllowed;
                    regexAllowed = true;
                    int close = scan(src.length(), true);
                    regexAllowed = saved;
                    String code = trim(src.substring(start, close));
                    pos = close + 1;
                    // Expressions that produce markup cut the text like a tag; the rest are values ({0}).
                    s.append(HTML_EXPR.matcher(code).find() || conditionalOrLogical(code) ? "<x/>" : "\u0000");
                    continue;
                }
                if (c == '\r') { // a template's raw line breaks are normalized to \n
                    s.append('\n');
                    pos += pos + 1 < src.length() && src.charAt(pos + 1) == '\n' ? 2 : 1;
                    continue;
                }
                s.append(c);
                pos++;
            }
            String text = s.toString();
            if (MARKUP.matcher(text).find() || text.contains("<x/>")) addFromMarkup(text); else add(text);
        }

        void regex() {
            pos++;
            boolean inClass = false;
            while (pos < src.length()) {
                char c = src.charAt(pos);
                if (c == '\\') { pos += 2; continue; }
                if (c == '\n') break;
                if (c == '[') inClass = true;
                else if (c == ']') inClass = false;
                else if (c == '/' && !inClass) { pos++; break; }
                pos++;
            }
            while (pos < src.length() && Character.isLetter(src.charAt(pos))) pos++;
        }

        void escape(StringBuilder sb) {
            char n = pos + 1 < src.length() ? src.charAt(pos + 1) : '\\';
            pos += 2;
            switch (n) {
                case 'n' -> sb.append('\n');
                case 't' -> sb.append('\t');
                case 'r' -> sb.append('\r');
                case 'b' -> sb.append('\b');
                case 'f' -> sb.append('\f');
                case 'v' -> sb.append('\u000B');
                case '0' -> sb.append('\0');
                case '\r' -> { if (pos < src.length() && src.charAt(pos) == '\n') pos++; }
                case '\n', '\u2028', '\u2029' -> { }
                case 'x' -> { sb.append((char) Integer.parseInt(src.substring(pos, pos + 2), 16)); pos += 2; }
                case 'u' -> {
                    if (src.charAt(pos) == '{') {
                        int close = src.indexOf('}', pos);
                        sb.appendCodePoint(Integer.parseInt(src.substring(pos + 1, close), 16));
                        pos = close + 1;
                    } else {
                        sb.append((char) Integer.parseInt(src.substring(pos, pos + 4), 16));
                        pos += 4;
                    }
                }
                default -> sb.append(n);
            }
        }

        /** True when the top level of the expression is a conditional (a ? b : c) or a logical one (a && b, a || b, a ?? b). */
        static boolean conditionalOrLogical(String code) {
            int depth = 0;
            boolean found = false;
            for (int i = 0; i < code.length(); i++) {
                char c = code.charAt(i);
                if (c == '"' || c == '\'' || c == '`') {
                    i = skipQuoted(code, i);
                } else if (c == '(' || c == '[' || c == '{') {
                    depth++;
                } else if (c == ')' || c == ']' || c == '}') {
                    depth--;
                } else if (depth == 0) {
                    String two = i + 1 < code.length() ? code.substring(i, i + 2) : "";
                    if (two.equals("=>")) return false; // an arrow function
                    if (two.equals("&&") || two.equals("||") || two.equals("??")) { found = true; i++; }
                    else if (c == '?' && !(i + 1 < code.length() && code.charAt(i + 1) == '.' && !(i + 2 < code.length() && Character.isDigit(code.charAt(i + 2))))) found = true;
                }
            }
            return found;
        }

        static int skipQuoted(String code, int i) {
            char quote = code.charAt(i);
            int depth = 0;
            for (i++; i < code.length(); i++) {
                char c = code.charAt(i);
                if (c == '\\') { i++; continue; }
                if (quote == '`' && c == '$' && i + 1 < code.length() && code.charAt(i + 1) == '{') { depth++; i++; continue; }
                if (quote == '`' && depth > 0 && c == '}') { depth--; continue; }
                if (c == quote && depth == 0) return i;
            }
            return i;
        }
    }

    /** A minimal JSON reader for the knowledge base: objects, arrays, strings, numbers, booleans and null. */
    static final class Json {
        final String s;
        int i;

        Json(String s) {
            this.s = s;
        }

        Object value() {
            skip();
            char c = s.charAt(i);
            if (c == '{') {
                Map<String, Object> map = new HashMap<>();
                i++;
                skip();
                if (s.charAt(i) == '}') { i++; return map; }
                while (true) {
                    skip();
                    String k = str();
                    skip();
                    i++; // :
                    map.put(k, value());
                    skip();
                    if (s.charAt(i++) == '}') return map;
                }
            }
            if (c == '[') {
                List<Object> list = new ArrayList<>();
                i++;
                skip();
                if (s.charAt(i) == ']') { i++; return list; }
                while (true) {
                    list.add(value());
                    skip();
                    if (s.charAt(i++) == ']') return list;
                }
            }
            if (c == '"') return str();
            int start = i;
            while (i < s.length() && ",}] \t\r\n".indexOf(s.charAt(i)) < 0) i++;
            String word = s.substring(start, i);
            return switch (word) {
                case "true" -> Boolean.TRUE;
                case "false" -> Boolean.FALSE;
                case "null" -> null;
                default -> Double.valueOf(word);
            };
        }

        String str() {
            StringBuilder sb = new StringBuilder();
            i++;
            while (true) {
                char c = s.charAt(i++);
                if (c == '"') return sb.toString();
                if (c != '\\') { sb.append(c); continue; }
                char e = s.charAt(i++);
                switch (e) {
                    case 'n' -> sb.append('\n');
                    case 't' -> sb.append('\t');
                    case 'r' -> sb.append('\r');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'u' -> { sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; }
                    default -> sb.append(e);
                }
            }
        }

        void skip() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        }
    }
}
