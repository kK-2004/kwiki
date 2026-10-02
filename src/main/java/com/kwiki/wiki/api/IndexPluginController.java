package com.kwiki.wiki.api;

import com.kk2004.common.response.TransDTO;
import com.kwiki.indexing.config.IndexingDefaultsProperties;
import com.kwiki.indexing.gray.ParserCatalog;
import com.kwiki.indexing.pipeline.VersionedIndexingPipelineRegistry;
import com.kwiki.indexing.schema.ChunkIndexSchemaRegistry;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 管理端插件目录；可用性原因只展示插件提供的脱敏说明。 */
@RestController
@RequestMapping("/api/v1/admin/search-indexes")
@PreAuthorize("hasRole('ADMIN')")
public class IndexPluginController {
    private final ChunkIndexSchemaRegistry schemas;
    private final ParserCatalog parsers;
    private final IndexingDefaultsProperties defaults;

    public IndexPluginController(ChunkIndexSchemaRegistry schemas, ParserCatalog parsers,
                                 IndexingDefaultsProperties defaults) {
        this.schemas = schemas;
        this.parsers = parsers;
        this.defaults = defaults;
    }

    public record SchemaOption(String id, String label, boolean available, String unavailableReason,
                               List<String> capabilities) { }
    public record ChunkerOption(String id, String label, boolean available, String unavailableReason) { }
    public record Defaults(String indexSchema, List<String> parsers) { }
    public record Catalog(List<SchemaOption> schemas, List<ParserCatalog.ParserOption> parsers,
                          List<ChunkerOption> chunkers, Defaults defaults) { }

    @GetMapping("/plugins")
    public TransDTO<Catalog> plugins() {
        List<SchemaOption> options = schemas.all().stream().map(schema -> {
            var availability = schema.availability();
            return new SchemaOption(schema.id(), schema.label(), availability.available(),
                    availability.unavailableReason(), schema.capabilities().stream().sorted().map(Enum::name).toList());
        }).toList();
        return TransDTO.success(new Catalog(options, parsers.options(),
                List.of(new ChunkerOption(VersionedIndexingPipelineRegistry.SUPPORTED_CHUNKER_VERSION,
                        "父子分块 v1", true, null)), new Defaults(defaults.indexSchema(), defaults.parsers())));
    }
}
