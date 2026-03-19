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

    public FileSystemTools(@Value("${mcp.fs.basedir:/data}") String basedir) {
        this.baseDir = Path.of(basedir);
    }

    @Tool(name = "fs_list", description = "Lists files and directories in a directory. Path is relative to the configured base directory.")
    public List<FileInfo> listFiles(
            @ToolParam(description = "Relative directory path, e.g. Projects/Misc") String relativePath) {
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

    @Tool(name = "fs_read", description = "Reads the content of a text file with line numbering. "
            + "IMPORTANT: reads only 50 lines at a time (default). For large files, use offset to continue. "
            + "To search for specific content, prefer fs_grep which is more efficient. "
            + "Output: '<line_number>| <content>'.")
    public String readFile(
            @ToolParam(description = "Relative file path, e.g. cps4/index.html") String relativePath,
            @ToolParam(description = "Start line (0-based). Default: 0.", required = false) Integer offset,
            @ToolParam(description = "Number of lines to read (max 2000). Default: 50.", required = false) Integer limit) {
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

    @Tool(name = "fs_grep", description = "Searches for a pattern (text or regex) in files within a directory. "
            + "Returns matching lines with line numbers and context. Max 50 matches.")
    public String grepFiles(
            @ToolParam(description = "Relative path of the starting directory") String relativePath,
            @ToolParam(description = "Search pattern (plain text or Java regex)") String pattern,
            @ToolParam(description = "Glob pattern to filter files, e.g. *.html, *.css. Default: * (all)", required = false) String glob) {
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

    @Tool(name = "fs_write", description = "Writes (creates or overwrites) a text file. Path is relative to the base directory. Intermediate directories are created automatically. Max 500KB content.")
    public String writeFile(
            @ToolParam(description = "Relative file path to write, e.g. cps4/index.html") String relativePath,
            @ToolParam(description = "Full file content to write") String content) {
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

    @Tool(name = "fs_search", description = "Searches for files by name (glob pattern) in a directory, recursively up to 10 levels deep. Max 100 results.")
    public List<String> searchFiles(
            @ToolParam(description = "Relative path of the starting directory") String relativePath,
            @ToolParam(description = "Glob pattern, e.g. *.java, *.xml, pom*") String globPattern) {
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
