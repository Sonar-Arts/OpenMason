package com.openmason.main.boundary;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Game formats belong to their game (#88): Stonebreak's SBO, SBE and SBT live in Stonebreak's
 * {@code stonebreak-formats} module and reach the engine and the editor only through the format plugin
 * points ({@code FormatCodec}, {@code TextureDecoder}, {@code EntityModelImporter},
 * {@code AssetFormatContribution}). No main source file of any OpenMason module may import or name an
 * SB* format type, a {@code format.sb[oet]} package, or a {@code .sbo/.sbe/.sbt} extension. Comments are
 * not code and are ignored. (SBUI, the UI runtime's document format, is the engine's own and allowed.)
 */
class NoGameFormatsTest {

    private static final List<Pattern> FORBIDDEN = List.of(
            Pattern.compile("com\\.openmason\\.engine\\.format\\.sb[oet]\\b"),
            Pattern.compile("com\\.stonebreak\\."),
            Pattern.compile("\\b(SBO|SBE|SBT)[A-Z][A-Za-z0-9_]*"),
            Pattern.compile("\\bSb[oe][A-Z][A-Za-z0-9_]*"),
            Pattern.compile("\\.sb[oet]\\b", Pattern.CASE_INSENSITIVE));

    @Test
    void noOpenMasonMainSourceNamesAGameFormat() throws IOException {
        Path repo = repoRoot();
        List<Path> roots = new ArrayList<>();
        roots.add(repo.resolve("openmason-tool/src/main/java"));
        try (Stream<Path> modules = Files.list(repo.resolve("openmason-engine"))) {
            modules.map(m -> m.resolve("src/main/java")).filter(Files::isDirectory).forEach(roots::add);
        }
        assertTrue(roots.size() > 3, "expected the engine modules and the tool, got " + roots);

        List<String> offenders = new ArrayList<>();
        for (Path root : roots) {
            try (Stream<Path> files = Files.walk(root)) {
                files.filter(p -> p.toString().endsWith(".java")).sorted().forEach(p -> scan(repo, p, offenders));
            }
        }
        assertTrue(offenders.isEmpty(), "game format references in OpenMason code:\n  " + String.join("\n  ", offenders));
    }

    private static void scan(Path repo, Path file, List<String> offenders) {
        String code;
        try {
            code = stripComments(Files.readString(file));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        String[] lines = code.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            for (Pattern p : FORBIDDEN) {
                Matcher m = p.matcher(lines[i]);
                if (m.find()) {
                    offenders.add(repo.relativize(file) + ":" + (i + 1) + " '" + m.group() + "'");
                    break;
                }
            }
        }
    }

    /** {@code src} with comments blanked (line breaks kept, so line numbers stay true); string literals kept. */
    static String stripComments(String src) {
        StringBuilder out = new StringBuilder(src.length());
        int n = src.length();
        for (int i = 0; i < n; i++) {
            char c = src.charAt(i);
            char next = i + 1 < n ? src.charAt(i + 1) : 0;
            if (c == '"' || c == '\'') {
                // string, text block or char literal: copy verbatim
                boolean textBlock = c == '"' && src.startsWith("\"\"\"", i);
                String close = textBlock ? "\"\"\"" : String.valueOf(c);
                int j = i + close.length();
                while (j < n && !src.startsWith(close, j)) {
                    j += src.charAt(j) == '\\' ? 2 : 1;
                }
                j = Math.min(n, j + close.length());
                out.append(src, i, j);
                i = j - 1;
            } else if (c == '/' && next == '/') {
                while (i < n && src.charAt(i) != '\n') {
                    i++;
                }
                if (i < n) {
                    out.append('\n');
                }
            } else if (c == '/' && next == '*') {
                int end = src.indexOf("*/", i + 2);
                end = end < 0 ? n : end + 2;
                for (int k = i; k < end; k++) {
                    if (src.charAt(k) == '\n') {
                        out.append('\n');
                    }
                }
                i = end - 1;
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static Path repoRoot() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null && !Files.isDirectory(dir.resolve("openmason-engine"))) {
            dir = dir.getParent();
        }
        assertTrue(dir != null, "the OpenMason checkout root (with openmason-engine/) above the working directory");
        return dir;
    }
}
