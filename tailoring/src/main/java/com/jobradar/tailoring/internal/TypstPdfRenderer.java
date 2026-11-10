package com.jobradar.tailoring.internal;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Renders with the Typst CLI: writes the static template and the document as {@code cv.json}
 * into a fresh temporary directory and runs {@code typst compile}.
 *
 * <p>The template never changes per CV and reads its content as JSON strings, which Typst prints
 * literally. Bullet text therefore cannot inject Typst markup or code.
 */
@Component
@EnableConfigurationProperties(TailoringProperties.class)
class TypstPdfRenderer implements PdfRenderer {

    private static final String TEMPLATE = "/typst/cv.typ";
    private static final long TIMEOUT_SECONDS = 30;

    private final TailoringProperties properties;
    private final JsonMapper json;

    TypstPdfRenderer(TailoringProperties properties, JsonMapper json) {
        this.properties = properties;
        this.json = json;
    }

    @Override
    public byte[] render(CvDocument document) {
        Path dir = null;
        try {
            dir = Files.createTempDirectory("jobradar-cv");
            try (InputStream template = TypstPdfRenderer.class.getResourceAsStream(TEMPLATE)) {
                Files.copy(template, dir.resolve("cv.typ"));
            }
            Files.writeString(dir.resolve("cv.json"), json.writeValueAsString(document), StandardCharsets.UTF_8);

            // --root confines file access of the template to the temporary directory.
            Process process = new ProcessBuilder(
                            properties.typstBinary(), "compile", "--root", dir.toString(), "cv.typ", "cv.pdf")
                    .directory(dir.toFile())
                    .redirectErrorStream(true)
                    .start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException("Typst did not finish within " + TIMEOUT_SECONDS + " s");
            }
            if (process.exitValue() != 0) {
                throw new IllegalStateException("Typst failed: " + output.strip());
            }
            return Files.readAllBytes(dir.resolve("cv.pdf"));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not run Typst (" + properties.typstBinary() + ")", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while rendering", e);
        } finally {
            deleteQuietly(dir);
        }
    }

    /** The directory holds personal data, so it is removed even when rendering fails. */
    private static void deleteQuietly(Path dir) {
        if (dir == null) {
            return;
        }
        try (Stream<Path> files = Files.walk(dir)) {
            files.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        } catch (IOException ignored) {
            // Best effort: the operating system clears the temporary directory eventually.
        }
    }
}
