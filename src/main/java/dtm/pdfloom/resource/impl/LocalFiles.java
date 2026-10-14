package dtm.pdfloom.resource.impl;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.regex.Pattern;

public final class LocalFiles {
    private static final Pattern SCHEME = Pattern.compile("^[A-Za-z][A-Za-z0-9+.-]*:");
    private static final Pattern DRIVE = Pattern.compile("^[A-Za-z]:(/.*)?$");

    private LocalFiles() {
    }

    public static Path path(String reference, URI origin) {
        String value = reference.strip();
        if (value.isEmpty() || value.indexOf('\0') >= 0) {
            return null;
        }
        String slashed = value.replace('\\', '/');
        if (DRIVE.matcher(slashed).matches()) {
            return existing(slashed);
        }
        if (slashed.startsWith("/") && !slashed.startsWith("//")) {
            Path absolute = existing(slashed);
            return absolute != null && Files.isRegularFile(absolute) ? absolute : null;
        }
        if (slashed.toLowerCase(Locale.ROOT).startsWith("file:")) {
            String rest = slashed.substring(5);
            int start = 0;
            while (start < rest.length() && rest.charAt(start) == '/') {
                start++;
            }
            String stripped = rest.substring(start);
            if (DRIVE.matcher(stripped).matches()) {
                return existing(stripped);
            }
            return existing("/" + stripped);
        }
        if (SCHEME.matcher(slashed).find()
                || origin == null
                || !"file".equalsIgnoreCase(origin.getScheme())) {
            return null;
        }
        Path base;
        try {
            Path originPath = Path.of(origin);
            base = origin.getPath().endsWith("/") ? originPath : originPath.getParent();
        } catch (IllegalArgumentException exception) {
            return null;
        }
        if (base == null) {
            return null;
        }
        int query = indexOfAny(slashed, '?', '#');
        String relative = query < 0 ? slashed : slashed.substring(0, query);
        if (relative.startsWith("/")) {
            return null;
        }
        Path raw = resolve(base, relative);
        if (raw != null && Files.isRegularFile(raw)) {
            return raw;
        }
        Path decoded = resolve(base, decode(relative));
        return decoded == null ? raw : decoded;
    }

    private static Path existing(String value) {
        int query = indexOfAny(value, '?', '#');
        Path raw = of(value);
        if (raw != null && Files.isRegularFile(raw)) {
            return raw;
        }
        if (query > 0) {
            Path trimmed = of(value.substring(0, query));
            if (trimmed != null && Files.isRegularFile(trimmed)) {
                return trimmed;
            }
        }
        Path decoded = of(decode(query > 0 ? value.substring(0, query) : value));
        return decoded == null ? raw : decoded;
    }

    private static Path resolve(Path base, String relative) {
        try {
            return base.resolve(relative).toAbsolutePath().normalize();
        } catch (InvalidPathException exception) {
            return null;
        }
    }

    private static Path of(String value) {
        try {
            return Path.of(value).toAbsolutePath().normalize();
        } catch (InvalidPathException exception) {
            return null;
        }
    }

    private static int indexOfAny(String value, char first, char second) {
        int a = value.indexOf(first);
        int b = value.indexOf(second);
        if (a < 0) {
            return b;
        }
        if (b < 0) {
            return a;
        }
        return Math.min(a, b);
    }

    private static String decode(String value) {
        if (value.indexOf('%') < 0) {
            return value;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int index = 0;
        while (index < value.length()) {
            int current = value.codePointAt(index);
            if (current == '%' && index + 2 < value.length()) {
                int high = Character.digit(value.charAt(index + 1), 16);
                int low = Character.digit(value.charAt(index + 2), 16);
                if (high >= 0 && low >= 0) {
                    out.write(high * 16 + low);
                    index += 3;
                    continue;
                }
            }
            byte[] bytes = new String(Character.toChars(current)).getBytes(StandardCharsets.UTF_8);
            out.write(bytes, 0, bytes.length);
            index += Character.charCount(current);
        }
        return out.toString(StandardCharsets.UTF_8);
    }
}
