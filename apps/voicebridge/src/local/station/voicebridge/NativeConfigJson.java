package local.station.voicebridge;

import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/** Strict bounded JSON before data reaches Android's permissive JSONObject parser. */
final class NativeConfigJson {
    private NativeConfigJson() { }
    static JSONObject object(byte[] raw, int limit) throws Exception {
        if (raw == null || raw.length < 2 || raw.length > limit) throw invalid();
        String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(raw)).toString();
        Parser parser = new Parser(text);
        Object value = parser.value(0); parser.space();
        if (!(value instanceof JSONObject) || parser.index != text.length()) throw invalid();
        return (JSONObject) value;
    }
    static JSONObject copy(JSONObject value) throws Exception {
        return object(value.toString().getBytes(StandardCharsets.UTF_8), 262144);
    }
    static IllegalArgumentException invalid() { return new IllegalArgumentException("invalid_local_config"); }
    private static final class Parser {
        final String text; int index, nodes;
        Parser(String value) { text = value; }
        Object value(int depth) throws Exception {
            if (++nodes > 32768 || depth > 32) throw invalid();
            space(); if (index == text.length()) throw invalid();
            char c = text.charAt(index);
            if (c == '"') return string();
            if (take('{')) {
                JSONObject result = new JSONObject(); space(); if (take('}')) return result;
                while (true) {
                    space(); String key = string();
                    if (result.has(key)) throw invalid(); space(); require(':');
                    result.put(key, value(depth + 1)); space();
                    if (take('}')) return result; require(',');
                }
            }
            if (take('[')) {
                JSONArray result = new JSONArray(); space(); if (take(']')) return result;
                while (true) {
                    result.put(value(depth + 1)); space(); if (take(']')) return result; require(',');
                }
            }
            if (literal("true")) return Boolean.TRUE;
            if (literal("false")) return Boolean.FALSE;
            if (literal("null")) return JSONObject.NULL;
            int start = index; take('-');
            if (!take('0')) { if (!digit('1', '9')) throw invalid(); while (digit('0', '9')) index++; }
            if (take('.')) { if (!digit('0', '9')) throw invalid(); while (digit('0', '9')) index++; }
            if (take('e') || take('E')) {
                if (!take('+')) take('-'); if (!digit('0', '9')) throw invalid(); while (digit('0', '9')) index++;
            }
            String number = text.substring(start, index);
            if (number.indexOf('.') < 0 && number.indexOf('e') < 0 && number.indexOf('E') < 0) {
                try { return Long.valueOf(number); } catch (NumberFormatException invalid) { throw invalid(); }
            }
            double result;
            try { result = Double.parseDouble(number); } catch (NumberFormatException invalid) { throw invalid(); }
            if (Double.isNaN(result) || Double.isInfinite(result)) throw invalid(); return result;
        }
        String string() {
            require('"'); StringBuilder result = new StringBuilder();
            while (index < text.length()) {
                char c = text.charAt(index++);
                if (c == '"') { validateSurrogates(result); return result.toString(); }
                if (c < 32) throw invalid();
                if (c == '\\') {
                    if (index == text.length()) throw invalid(); char escaped = text.charAt(index++);
                    switch (escaped) {
                        case '"': c = '"'; break; case '\\': c = '\\'; break; case '/': c = '/'; break;
                        case 'b': c = '\b'; break; case 'f': c = '\f'; break; case 'n': c = '\n'; break;
                        case 'r': c = '\r'; break; case 't': c = '\t'; break;
                        case 'u':
                            if (index + 4 > text.length()) throw invalid();
                            int value = 0;
                            for (int i = 0; i < 4; i++) {
                                char digit = text.charAt(index++); int hex = Character.digit(digit, 16);
                                if (hex < 0 || digit > 127) throw invalid(); value = value * 16 + hex;
                            }
                            c = (char) value; break;
                        default: throw invalid();
                    }
                }
                result.append(c);
            }
            throw invalid();
        }
        void validateSurrogates(StringBuilder value) {
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                if (Character.isHighSurrogate(c)) {
                    if (++i >= value.length() || !Character.isLowSurrogate(value.charAt(i))) throw invalid();
                } else if (Character.isLowSurrogate(c)) throw invalid();
            }
        }
        boolean digit(char low, char high) { return index < text.length() && text.charAt(index) >= low && text.charAt(index) <= high; }
        boolean literal(String value) { if (!text.startsWith(value, index)) return false; index += value.length(); return true; }
        boolean take(char value) { if (index == text.length() || text.charAt(index) != value) return false; index++; return true; }
        void require(char value) { if (!take(value)) throw invalid(); }
        void space() { while (index < text.length() && " \r\n\t".indexOf(text.charAt(index)) >= 0) index++; }
    }
}
