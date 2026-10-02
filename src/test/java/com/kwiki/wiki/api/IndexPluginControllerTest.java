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
        @Bean IndexingDefaultsProperties defaults() { return SchemaTestFixtures.defaults(); }
        @Bean ParserCatalog parsers() {
            var readiness = mock(MultimodalSwitchReadiness.class);
            when(readiness.missingConfiguration()).thenReturn(List.of("KWIKI_VISION_API_KEY"));
            return new ParserCatalog(readiness);
        }
        @Bean IndexPluginController controller(ChunkIndexSchemaRegistry schemas, ParserCatalog parsers,
                                              IndexingDefaultsProperties defaults) {
            return new IndexPluginController(schemas, parsers, defaults);
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
                .andExpect(jsonPath("$.data.parsers[1].unavailableReason").value("缺少配置：KWIKI_VISION_API_KEY"))
                .andExpect(jsonPath("$.data.chunkers[0].id").value("kwiki-chunk-1"))
                .andExpect(jsonPath("$.data.defaults.indexSchema").value("kwiki-schema-1"));
    }
}
