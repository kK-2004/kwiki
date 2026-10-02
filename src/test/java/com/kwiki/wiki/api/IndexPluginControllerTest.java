package com.kwiki.wiki.api;

import com.kwiki.indexing.config.IndexingDefaultsProperties;
import com.kwiki.indexing.config.MultimodalSwitchReadiness;
import com.kwiki.indexing.gray.ParserCatalog;
import com.kwiki.indexing.schema.ChunkIndexSchemaRegistry;
import com.kwiki.indexing.schema.SchemaTestFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(IndexPluginController.class)
@Import(IndexPluginControllerTest.Config.class)
class IndexPluginControllerTest {
    @Configuration @EnableMethodSecurity
    static class Config {
        @Bean ChunkIndexSchemaRegistry schemas() {
            var unavailable = mock(com.kwiki.indexing.schema.ChunkIndexSchema.class);
            when(unavailable.id()).thenReturn("kwiki-schema-2");
            when(unavailable.label()).thenReturn("扩展字段格式");
            when(unavailable.availability()).thenReturn(com.kwiki.indexing.plugin.PluginAvailability.unavailable("缺少插件配置"));
            when(unavailable.capabilities()).thenReturn(java.util.Set.of());
            return new ChunkIndexSchemaRegistry(List.of(new com.kwiki.indexing.schema.KwikiSchemaV1(), unavailable));
        }
        @Bean com.kwiki.indexing.chunk.plugin.ChunkerRegistry chunkers() {
            return new com.kwiki.indexing.chunk.plugin.ChunkerRegistry(List.of(chunker("third-chunker", "第三分块器", false),
                    chunker("kwiki-chunk-1", "父子分块 v1", true)));
        }
        private com.kwiki.indexing.chunk.plugin.ChunkerPlugin chunker(String id, String label, boolean available) {
            var plugin = mock(com.kwiki.indexing.chunk.plugin.ChunkerPlugin.class);
            when(plugin.id()).thenReturn(id);
            when(plugin.label()).thenReturn(label);
            when(plugin.availability()).thenReturn(available ? com.kwiki.indexing.plugin.PluginAvailability.ok()
                    : com.kwiki.indexing.plugin.PluginAvailability.unavailable("测试分块器不可用"));
            return plugin;
        }
        @Bean IndexingDefaultsProperties defaults() { return SchemaTestFixtures.defaults(); }
        @Bean ParserCatalog parsers() {
            var readiness = mock(MultimodalSwitchReadiness.class);
            when(readiness.missingConfiguration()).thenReturn(List.of("KWIKI_VISION_API_KEY"));
            return new ParserCatalog(new com.kwiki.indexing.parse.plugin.DocumentParserRegistry(java.util.stream.Stream.concat(
                    com.kwiki.indexing.parse.plugin.ParserTestFixtures.unavailableTwo().all().stream(),
                    java.util.stream.Stream.of(new com.kwiki.indexing.parse.plugin.ParserTestFixtures.FakeParser("third-parser",
                            java.util.Set.of(), java.util.Set.of(com.kwiki.indexing.plugin.IndexCapability.ENTITY_LINKING), true))).toList()));
        }
        @Bean IndexPluginController controller(ChunkIndexSchemaRegistry schemas, ParserCatalog parsers,
                                              IndexingDefaultsProperties defaults, com.kwiki.indexing.chunk.plugin.ChunkerRegistry chunkers) {
            return new IndexPluginController(schemas, parsers, defaults, chunkers);
        }
    }
    @Autowired MockMvc mvc;

    @Test void 匿名用户被拒绝() throws Exception {
        mvc.perform(get("/api/v1/admin/search-indexes/plugins")).andExpect(status().isUnauthorized());
    }
    @Test @WithMockUser(roles="USER") void 普通用户被拒绝() throws Exception {
        mvc.perform(get("/api/v1/admin/search-indexes/plugins")).andExpect(status().isForbidden());
    }
    @Test @WithMockUser(roles="ADMIN") void 管理员获得插件能力与缺省选择() throws Exception {
        mvc.perform(get("/api/v1/admin/search-indexes/plugins")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.schemas[0].id").value("kwiki-schema-1"))
                .andExpect(jsonPath("$.data.schemas[0].label").value("图文 + 实体关联 v1"))
                .andExpect(jsonPath("$.data.schemas[0].available").value(true))
                .andExpect(jsonPath("$.data.schemas[0].capabilities", org.hamcrest.Matchers.contains("RESOURCE_IDS", "ENTITY_LINKING")))
                .andExpect(jsonPath("$.data.schemas[1].available").value(false))
                .andExpect(jsonPath("$.data.schemas[1].unavailableReason").value("缺少插件配置"))
                .andExpect(jsonPath("$.data.schemas[1].capabilities").isEmpty())
                .andExpect(jsonPath("$.data.parsers[0].requiredCapabilities").isEmpty())
                .andExpect(jsonPath("$.data.parsers[1].requiredCapabilities[0]").value("RESOURCE_IDS"))
                .andExpect(jsonPath("$.data.parsers[1].available").value(false))
                .andExpect(jsonPath("$.data.parsers[1].unavailableReason").value("测试不可用"))
                .andExpect(jsonPath("$.data.parsers[2].id").value("third-parser"))
                .andExpect(jsonPath("$.data.parsers[2].requiredCapabilities[0]").value("ENTITY_LINKING"))
                .andExpect(jsonPath("$.data.defaults.parsers[0]").value("kwiki-parse-2"))
                .andExpect(jsonPath("$.data.chunkers[0].id").value("kwiki-chunk-1"))
                .andExpect(jsonPath("$.data.chunkers[0].label").value("父子分块 v1"))
                .andExpect(jsonPath("$.data.chunkers[0].available").value(true))
                .andExpect(jsonPath("$.data.chunkers[1].id").value("third-chunker"))
                .andExpect(jsonPath("$.data.chunkers[1].label").value("第三分块器"))
                .andExpect(jsonPath("$.data.chunkers[1].available").value(false))
                .andExpect(jsonPath("$.data.chunkers[1].unavailableReason").value("测试分块器不可用"))
                .andExpect(jsonPath("$.data.defaults.chunker").value("kwiki-chunk-1"))
                .andExpect(jsonPath("$.data.defaults.indexSchema").value("kwiki-schema-1"));
    }
}
