package io.github.massimilianopili.mcp.filesystem;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

@Service
public class FileSystemTools {

    private final Path baseDir;

    public FileSystemTools(@Value("${mcp.fs.basedir:C:/NoCloud}") String basedir) {
        this.baseDir = Path.of(basedir);
    }

    @Tool(name = "fs_list", description = "Elenca file e cartelle in una directory. Il percorso e' relativo alla directory base configurata (default: C:/NoCloud).")
    public List<FileInfo> listFiles(
            @ToolParam(description = "Percorso relativo della directory, es: Progetti/Vari") String relativePath) {
        Path dir = resolveAndValidate(relativePath);
        if (!Files.isDirectory(dir)) {
            throw new IllegalArgumentException("Il percorso non e' una directory: " + relativePath);
        }
        try (var stream = Files.list(dir)) {
            return stream.map(p -> new FileInfo(
                    p.getFileName().toString(),
                    Files.isDirectory(p) ? "directory" : "file",
                    safeSize(p),
                    safeLastModified(p)
            )).toList();
        } catch (IOException e) {
            throw new RuntimeException("Errore lettura directory: " + e.getMessage(), e);
        }
    }

    @Tool(name = "fs_read", description = "Legge il contenuto di un file di testo con numerazione righe. "
            + "IMPORTANTE: legge solo 50 righe alla volta (default). Per file grandi, usa offset per continuare. "
            + "Per cercare contenuti specifici, preferisci fs_grep che e' piu' efficiente. "
            + "Output: '<line_number>| <content>'.")
    public String readFile(
            @ToolParam(description = "Percorso relativo del file, es: cps4/index.html") String relativePath,
            @ToolParam(description = "Riga di partenza (0-based). Default: 0.", required = false) Integer offset,
            @ToolParam(description = "Numero di righe da leggere (max 2000). Default: 50.", required = false) Integer limit) {
        Path file = resolveAndValidate(relativePath);
        if (Files.isDirectory(file)) {
            throw new IllegalArgumentException("Il percorso e' una directory, non un file");
        }
        if (!Files.exists(file)) {
            throw new IllegalArgumentException("File non trovato: " + relativePath);
        }
        int off = (offset != null && offset >= 0) ? offset : 0;
        int lim = (limit != null && limit > 0) ? Math.min(limit, 2000) : 50;
        try {
            List<String> allLines = Files.readAllLines(file);
            int totalLines = allLines.size();
            if (off >= totalLines) {
                return String.format("[File has %d lines, offset %d is beyond end]", totalLines, off);
            }
            int end = Math.min(off + lim, totalLines);
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("[%s — %d lines total, showing %d-%d]\n", relativePath, totalLines, off + 1, end));
            for (int i = off; i < end; i++) {
                sb.append(String.format("%4d| %s\n", i + 1, allLines.get(i)));
            }
            if (end < totalLines) {
                sb.append(String.format("[... %d more lines. Use offset=%d to continue reading]\n", totalLines - end, end));
            }
            return sb.toString();
        } catch (IOException e) {
            throw new RuntimeException("Errore lettura file: " + e.getMessage(), e);
        }
    }

    @Tool(name = "fs_grep", description = "Cerca un pattern (testo o regex) nei file di una directory. "
            + "Restituisce le righe corrispondenti con numero di riga e contesto. Massimo 50 match.")
    public String grepFiles(
            @ToolParam(description = "Percorso relativo della directory di partenza") String relativePath,
            @ToolParam(description = "Pattern di ricerca (testo semplice o regex Java)") String pattern,
            @ToolParam(description = "Pattern glob per filtrare i file, es: *.html, *.css. Default: * (tutti)", required = false) String glob) {
        Path dir = resolveAndValidate(relativePath);
        if (!Files.isDirectory(dir)) {
            throw new IllegalArgumentException("Il percorso non e' una directory: " + relativePath);
        }
        String fileGlob = (glob != null && !glob.isBlank()) ? glob : "*";
        java.util.regex.Pattern regex;
        try {
            regex = java.util.regex.Pattern.compile(pattern, java.util.regex.Pattern.CASE_INSENSITIVE);
        } catch (java.util.regex.PatternSyntaxException e) {
            // Fallback: literal match
            regex = java.util.regex.Pattern.compile(java.util.regex.Pattern.quote(pattern),
                    java.util.regex.Pattern.CASE_INSENSITIVE);
        }
        PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + fileGlob);
        List<String> results = new ArrayList<>();
        java.util.regex.Pattern finalRegex = regex;
        try (Stream<Path> stream = Files.walk(dir, 5)) {
            stream.filter(p -> !Files.isDirectory(p))
                  .filter(p -> matcher.matches(p.getFileName()))
                  .forEach(p -> {
                      if (results.size() >= 50) return;
                      try {
                          List<String> lines = Files.readAllLines(p);
                          String relFile = baseDir.relativize(p).toString().replace('\\', '/');
                          for (int i = 0; i < lines.size() && results.size() < 50; i++) {
                              if (finalRegex.matcher(lines.get(i)).find()) {
                                  results.add(String.format("%s:%d| %s", relFile, i + 1, lines.get(i).trim()));
                              }
                          }
                      } catch (IOException ignored) {
                          // Skip binary/unreadable files
                      }
                  });
        } catch (IOException e) {
            throw new RuntimeException("Errore ricerca: " + e.getMessage(), e);
        }
        if (results.isEmpty()) {
            return "Nessun match trovato per '" + pattern + "' in " + relativePath;
        }
        return String.format("[%d match trovati]\n%s", results.size(), String.join("\n", results));
    }

    @Tool(name = "fs_write", description = "Scrive (crea o sovrascrive) un file di testo. Il percorso e' relativo alla directory base. Le directory intermedie vengono create automaticamente. Massimo 500KB di contenuto.")
    public String writeFile(
            @ToolParam(description = "Percorso relativo del file da scrivere, es: cps4/index.html") String relativePath,
            @ToolParam(description = "Contenuto completo del file da scrivere") String content) {
        Path file = resolveAndValidate(relativePath);
        if (content.length() > 500_000) {
            throw new IllegalArgumentException("Contenuto troppo grande: " + content.length() + " caratteri (max 500000)");
        }
        try {
            Path parent = file.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
            }
            Files.writeString(file, content);
            return "File scritto: " + relativePath + " (" + content.length() + " caratteri)";
        } catch (IOException e) {
            throw new RuntimeException("Errore scrittura file: " + e.getMessage(), e);
        }
    }

    @Tool(name = "fs_search", description = "Cerca file per nome (glob pattern) in una directory, ricorsivamente fino a 10 livelli di profondita'. Massimo 100 risultati.")
    public List<String> searchFiles(
            @ToolParam(description = "Percorso relativo della directory di partenza") String relativePath,
            @ToolParam(description = "Pattern glob, es: *.java, *.xml, pom*") String globPattern) {
        Path dir = resolveAndValidate(relativePath);
        if (!Files.isDirectory(dir)) {
            throw new IllegalArgumentException("Il percorso non e' una directory: " + relativePath);
        }
        try (var stream = Files.walk(dir, 10)) {
            PathMatcher matcher = FileSystems.getDefault()
                    .getPathMatcher("glob:" + globPattern);
            return stream
                    .filter(p -> !Files.isDirectory(p))
                    .filter(p -> matcher.matches(p.getFileName()))
                    .map(p -> baseDir.relativize(p).toString().replace('\\', '/'))
                    .limit(100)
                    .toList();
        } catch (IOException e) {
            throw new RuntimeException("Errore ricerca: " + e.getMessage(), e);
        }
    }

    private Path resolveAndValidate(String relativePath) {
        Path resolved = baseDir.resolve(relativePath).normalize();
        if (!resolved.startsWith(baseDir)) {
            throw new SecurityException("Accesso negato: percorso fuori dalla directory base");
        }
        return resolved;
    }

    private long safeSize(Path p) {
        try {
            return Files.isDirectory(p) ? -1 : Files.size(p);
        } catch (IOException e) {
            return -1;
        }
    }

    private String safeLastModified(Path p) {
        try {
            return Files.getLastModifiedTime(p).toString();
        } catch (IOException e) {
            return "unknown";
        }
    }
}
