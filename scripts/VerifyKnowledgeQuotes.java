import java.io.IOException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Checks that every quote in knowledge/Guidelines.json appears verbatim in the downloaded official documents.
 *
 * Usage: java scripts/VerifyKnowledgeQuotes.java src/main/resources/knowledge/Guidelines.json FOLDER
 * The folder must contain the text of each source (see docs/Rag.md): epa.txt, who-ph.txt, mahc-code.txt,
 * fao-01.txt and fao-06.txt. Only whitespace, line-break hyphens and typographic quotes are normalized.
 * Quotes with "[...]" are checked part by part.
 */
public class VerifyKnowledgeQuotes {

    static final Map<String, List<String>> SOURCES = new LinkedHashMap<>();
    static {
        SOURCES.put("epa-sdws", List.of("epa.txt"));
        SOURCES.put("who-gdwq-ph", List.of("who-ph.txt"));
        SOURCES.put("cdc-mahc-2024", List.of("mahc-code.txt"));
        SOURCES.put("fao-ipd29", List.of("fao-01.txt", "fao-06.txt"));
    }

    // Python's \s on text: ASCII and Unicode whitespace.
    static final String WS = "[\\t\\n\\x0B\\f\\r\\x1c-\\x1f \\x85\\u00a0\\u1680\\u2000-\\u200a\\u2028\\u2029\\u202f\\u205f\\u3000]";
    static final Pattern SOFT_HYPHEN = Pattern.compile("­" + WS + "*");
    static final Pattern SPACES = Pattern.compile(WS + "+");
    static final Pattern SPACE_BEFORE_PUNCTUATION = Pattern.compile(" ([,.;:)])");

    static String normalize(String text) {
        text = SOFT_HYPHEN.matcher(text).replaceAll("").replace("’", "'").replace("–", "-");
        text = SPACES.matcher(text).replaceAll(" ");
        text = SPACE_BEFORE_PUNCTUATION.matcher(text).replaceAll("$1");
        return text.replace("( ", "(").toLowerCase(Locale.ROOT);
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            System.err.println("Uso: java scripts/VerifyKnowledgeQuotes.java Guidelines.json CARPETA");
            System.exit(2);
        }
        Map<?, ?> kb = (Map<?, ?>) new Json(Files.readString(Path.of(args[0]), StandardCharsets.UTF_8)).value();
        Map<String, String> texts = new HashMap<>();
        for (var source : SOURCES.entrySet()) {
            List<String> parts = new ArrayList<>();
            for (String file : source.getValue()) parts.add(readLenient(Path.of(args[1], file)));
            texts.put(source.getKey(), normalize(String.join(" ", parts)));
        }
        List<?> chunks = (List<?>) kb.get("chunks");
        int missing = 0;
        for (Object o : chunks) {
            Map<?, ?> chunk = (Map<?, ?>) o;
            for (String raw : ((String) chunk.get("quote")).split(Pattern.quote("[...]"), -1)) {
                String part = raw.strip();
                if (!texts.get((String) chunk.get("sourceId")).contains(normalize(part))) {
                    missing++;
                    System.out.println("NOT FOUND: " + chunk.get("id") + " :: " + part.substring(0, Math.min(90, part.length())));
                }
            }
        }
        System.out.println(chunks.size() + " fragments, " + missing + " parts not found");
        System.exit(missing == 0 ? 0 : 1);
    }

    // Like Python's errors="ignore": bytes that are not UTF-8 are dropped.
    static String readLenient(Path file) throws IOException {
        return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.IGNORE)
                .onUnmappableCharacter(CodingErrorAction.IGNORE)
                .decode(java.nio.ByteBuffer.wrap(Files.readAllBytes(file)))
                .toString();
    }

    /** A minimal JSON reader: objects, arrays, strings, numbers, booleans and null. */
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
