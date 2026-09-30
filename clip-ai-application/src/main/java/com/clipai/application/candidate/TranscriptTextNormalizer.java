package com.clipai.application.candidate;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

public final class TranscriptTextNormalizer {
    private static final Pattern NON_LETTER_OR_DIGIT = Pattern.compile("[^\\p{L}\\p{N}]+");
    private static final Pattern DIACRITICS = Pattern.compile("\\p{M}+");
    private static final Pattern MOJIBAKE_MARKERS = Pattern.compile("[ÃÂâ]");
    private static final Charset WINDOWS_1252 = Charset.forName("windows-1252");

    private TranscriptTextNormalizer() {
    }

    public static String normalize(String text) {
        String repaired = repairMojibake(text);
        String decomposed = Normalizer.normalize(repaired.toLowerCase(Locale.ROOT), Normalizer.Form.NFD);
        String withoutDiacritics = DIACRITICS.matcher(decomposed).replaceAll("");
        return NON_LETTER_OR_DIGIT.matcher(withoutDiacritics).replaceAll(" ").trim();
    }

    private static String repairMojibake(String text) {
        if (text == null || !MOJIBAKE_MARKERS.matcher(text).find()) {
            return text == null ? "" : text;
        }
        var encoder = WINDOWS_1252.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(text.length());
            var input = encoder.encode(java.nio.CharBuffer.wrap(text));
            while (input.hasRemaining()) {
                bytes.write(input.get() & 0xff);
            }
            String decoded = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes.toByteArray()))
                    .toString();
            return markerCount(decoded) < markerCount(text) ? decoded : text;
        } catch (CharacterCodingException exception) {
            return text;
        }
    }

    private static int markerCount(String text) {
        var matcher = MOJIBAKE_MARKERS.matcher(text);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }
}
