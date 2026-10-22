package io.jcordis.loader;

import java.io.File;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

/**
 * Compiles a directory of plugin sources with the system javac, the Java
 * counterpart of Cordis's dynamic ESM import: a source tree becomes a
 * throwaway class directory loaded by its own {@link PluginClassLoader}, so
 * recompiling and discarding the loader swaps the plugin wholesale.
 *
 * <p>Requires a JDK at runtime ({@link ToolProvider#getSystemJavaCompiler} is
 * absent on a bare JRE — a clear error explains the requirement).
 */
final class SourceCompiler {

    private SourceCompiler() {}

    /** Compiled output of one {@link #compile} invocation. */
    record Compilation(Path classDirectory, List<String> classNames) {}

    /**
     * Compiles every {@code .java} file under {@code sourceDir} into a fresh
     * temporary class directory.
     *
     * @throws IllegalArgumentException for a missing/empty source tree or a JDK-less runtime
     * @throws CompilationException     when javac reports errors (diagnostics included)
     */
    static Compilation compile(Path sourceDir) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalArgumentException(
                    "dynamic source compilation requires a JDK (no system JavaCompiler on this runtime): " + sourceDir);
        }
        if (!Files.isDirectory(sourceDir)) {
            throw new IllegalArgumentException("source directory not found: " + sourceDir);
        }
        List<Path> sources = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(sourceDir)) {
            stream.filter(file -> file.toString().endsWith(".java")).forEach(sources::add);
        }
        if (sources.isEmpty()) {
            throw new IllegalArgumentException("no .java sources under " + sourceDir);
        }

        Path output = Files.createTempDirectory("jcordis-src-classes");
        javax.tools.DiagnosticCollector<javax.tools.JavaFileObject> diagnostics =
                new javax.tools.DiagnosticCollector<>();
        boolean success;
        try (javax.tools.StandardJavaFileManager fileManager =
                compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {
            List<File> sourceFiles = sources.stream().map(Path::toFile).toList();
            Iterable<? extends javax.tools.JavaFileObject> units = fileManager.getJavaFileObjectsFromFiles(sourceFiles);
            List<String> options = List.of(
                    "-classpath",
                    System.getProperty("java.class.path"),
                    "-encoding",
                    StandardCharsets.UTF_8.name(),
                    "-d",
                    output.toString());
            success = Boolean.TRUE.equals(
                    compiler.getTask(new StringWriter(), fileManager, diagnostics, options, null, units)
                            .call());
        }
        if (!success) {
            deleteRecursively(output);
            throw new CompilationException("compilation failed for " + sourceDir, render(diagnostics));
        }
        return new Compilation(output, classNamesOf(sourceDir, sources));
    }

    /** Renders the collected diagnostics (file:line: message per line). */
    private static String render(javax.tools.DiagnosticCollector<javax.tools.JavaFileObject> diagnostics) {
        StringBuilder text = new StringBuilder();
        for (javax.tools.Diagnostic<? extends javax.tools.JavaFileObject> diagnostic : diagnostics.getDiagnostics()) {
            text.append(diagnostic.getCode())
                    .append(' ')
                    .append(diagnostic.getKind())
                    .append(" at ")
                    .append(diagnostic.getLineNumber())
                    .append(':')
                    .append(diagnostic.getColumnNumber())
                    .append(" — ")
                    .append(diagnostic.getMessage(java.util.Locale.ROOT))
                    .append(System.lineSeparator());
        }
        return text.toString();
    }

    /** FQCNs of the compiled sources, derived from their paths relative to the source root. */
    private static List<String> classNamesOf(Path sourceDir, List<Path> sources) {
        List<String> names = new ArrayList<>();
        for (Path source : sources) {
            String relative = sourceDir
                    .relativize(source)
                    .toString()
                    .replace(File.separatorChar, '.')
                    .replaceAll("\\.java$", "");
            names.add(relative);
        }
        return names;
    }

    /** Deletes a compiled class directory (best effort — a failed close must not fail the caller). */
    static void deleteRecursively(Path directory) {
        try (Stream<Path> stream = Files.walk(directory)) {
            stream.sorted(java.util.Comparator.reverseOrder()).forEach(file -> {
                try {
                    Files.delete(file);
                } catch (IOException ignored) {
                    // best effort: an undeleted temp file is not worth failing for
                }
            });
        } catch (IOException ignored) {
            // directory already gone
        }
    }

    /** javac reported errors; the diagnostics text carries the compiler output. */
    static final class CompilationException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        CompilationException(String message, String diagnostics) {
            super(message + "\n" + diagnostics);
        }
    }
}
