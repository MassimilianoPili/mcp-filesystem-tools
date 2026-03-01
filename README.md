# MCP Filesystem Tools

Spring Boot starter providing MCP tools for file system operations with built-in path security. List, read, and search files within a sandboxed base directory.

## Installation

```xml
<dependency>
    <groupId>io.github.massimilianopili</groupId>
    <artifactId>mcp-filesystem-tools</artifactId>
    <version>0.1.0</version>
</dependency>
```

Requires Java 21+ and Spring AI 1.0.0+.

## Tools

| Tool | Description |
|------|-------------|
| `fs_list` | List files and directories at a given path |
| `fs_read` | Read file contents (max 100KB) |
| `fs_search` | Search files by name pattern (max 10 levels, 100 results) |

## Configuration

```properties
# Base directory for all file operations (default: C:/NoCloud)
MCP_FS_BASEDIR=/data/myproject
```

All paths are resolved relative to `MCP_FS_BASEDIR`. Path traversal (`../`) is blocked.

## How It Works

- Uses `@Tool` (Spring AI) for synchronous MCP tool methods
- Activated by `mcp.filesystem.enabled=true` (default: true, `matchIfMissing`)
- Path security enforced: all operations sandboxed within `MCP_FS_BASEDIR`

## Requirements

- Java 21+
- Spring Boot 3.4+
- Spring AI 1.0.0+

## License

[MIT License](LICENSE)
