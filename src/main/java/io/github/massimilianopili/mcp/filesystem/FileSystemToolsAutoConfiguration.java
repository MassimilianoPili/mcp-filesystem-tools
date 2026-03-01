package io.github.massimilianopili.mcp.filesystem;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

@AutoConfiguration
@ConditionalOnProperty(name = "mcp.filesystem.enabled", havingValue = "true", matchIfMissing = true)
@Import(FileSystemTools.class)
public class FileSystemToolsAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(name = "fileSystemToolCallbackProvider")
    public ToolCallbackProvider fileSystemToolCallbackProvider(FileSystemTools fsTools) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(fsTools)
                .build();
    }
}
