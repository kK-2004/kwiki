package com.kwiki.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kwiki.rag.retrieval.HybridRetrievalOrchestrator;
import com.kwiki.rag.retrieval.ParentEvidenceChunk;
import com.kwiki.testutil.StandardTestProperties;
import com.kwiki.testutil.WikiMockBeans;
import com.kwiki.wiki.domain.AppUser;
import com.kwiki.wiki.domain.KnowledgeBase;
import com.kwiki.wiki.domain.KnowledgeBaseMember;
import com.kwiki.wiki.persistence.AppUserRepository;
import com.kwiki.wiki.persistence.KnowledgeBaseMemberRepository;
import com.kwiki.wiki.persistence.KnowledgeBaseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MCP 端到端认证与控权：动态注册公共客户端 → 账号登录 → 授权码+PKCE →
 * 不透明访问令牌 → Streamable HTTP 上的工具调用。覆盖成功路径、
 * 未认证 401（含 resource_metadata 指引）、跨知识库越权拒绝、
 * 撤销后令牌立即失效，以及刷新令牌轮换。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(WikiMockBeans.class)
class McpOAuthIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String REDIRECT_URI = "http://127.0.0.1:9953/callback";

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        StandardTestProperties.register(registry);
        registry.add("kwiki.mcp.enabled", () -> "true");
        registry.add("kwiki.mcp.public-base-url", () -> "http://kwiki.test");
        registry.add("kwiki.mcp.auth-store", () -> "memory");
        registry.add("kwiki.mcp.access-token-ttl", () -> "30m");
        registry.add("logging.level.org.springframework.security", () -> "TRACE");
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    AppUserRepository users;

    @Autowired
    KnowledgeBaseRepository knowledgeBases;

    @Autowired
    KnowledgeBaseMemberRepository members;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;

    @MockBean
    HybridRetrievalOrchestrator retrieval;

    private final SecureRandom random = new SecureRandom();

    @BeforeEach
    void seedUsers() {
        AppUser kk = user(1L, "kk", true);
        AppUser alice = user(7L, "alice", false);
        AppUser bob = user(8L, "bob", false);
        when(users.findById(1L)).thenReturn(Optional.of(kk));
        when(users.findById(7L)).thenReturn(Optional.of(alice));
        when(users.findById(8L)).thenReturn(Optional.of(bob));
        when(users.findByUsername("kk")).thenReturn(Optional.of(kk));
        when(users.findByUsername("alice")).thenReturn(Optional.of(alice));
        when(users.findByUsername("bob")).thenReturn(Optional.of(bob));

        KnowledgeBase kb101 = knowledgeBase(101L, "工程手册");
        KnowledgeBase kb102 = knowledgeBase(102L, "产品百科");
        KnowledgeBase kb103 = knowledgeBase(103L, "机密库");
        when(knowledgeBases.findAll()).thenReturn(List.of(kb101, kb102, kb103));
        when(knowledgeBases.findAllById(any()))
                .thenAnswer(invocation -> {
                    Iterable<Long> ids = invocation.getArgument(0);
                    java.util.List<KnowledgeBase> result = new java.util.ArrayList<>();
                    for (Long id : ids) {
                        if (id == 101L) {
                            result.add(kb101);
                        } else if (id == 102L) {
                            result.add(kb102);
                        } else if (id == 103L) {
                            result.add(kb103);
                        }
                    }
                    return result;
                });
        KnowledgeBaseMember member101 = membership(101L);
        KnowledgeBaseMember member102 = membership(102L);
        when(members.findByUserId(7L)).thenReturn(List.of(member101, member102));
        when(members.findByUserId(8L)).thenReturn(List.of());

        // 授权作用域解析在无 Redis/MySQL 时经 JdbcTemplate 兜底查询：
        // alice 是 101/102 的活跃成员；页面级作用域为空。
        when(jdbcTemplate.query(contains("knowledge_base"),
                ArgumentMatchers.<org.springframework.jdbc.core.RowMapper<Long>>any(),
                any(Object[].class))).thenReturn(List.of(101L, 102L));
        when(jdbcTemplate.query(contains("wiki_page"),
                ArgumentMatchers.<org.springframework.jdbc.core.RowMapper<Long>>any(),
                any(Object[].class))).thenReturn(List.of());
        when(jdbcTemplate.queryForObject(any(String.class), any(Class.class), any(Object[].class)))
                .thenReturn(null);

        ParentEvidenceChunk parent = new ParentEvidenceChunk(
                "parent-1", 101L, "PAGE", 5001L, 88L, "运维 > 部署",
                "kwiki 的部署流程：先构建镜像，再滚动更新。", 0.83, List.of());
        when(retrieval.retrieve(any(), any(), any(), anyInt(), anyLong()))
                .thenReturn(new HybridRetrievalOrchestrator.RetrievalOutcome(
                        List.of(parent), List.of(), false));
    }

    private AppUser user(long id, String username, boolean admin) {
        AppUser user = mock(AppUser.class);
        when(user.getId()).thenReturn(id);
        when(user.getUsername()).thenReturn(username);
        when(user.isAdmin()).thenReturn(admin);
        when(user.isActive()).thenReturn(true);
        when(user.getPasswordHash())
                .thenReturn(passwordEncoder.encode(username + "-pass"));
        return user;
    }

    private KnowledgeBase knowledgeBase(long id, String name) {
        KnowledgeBase kb = mock(KnowledgeBase.class);
        when(kb.getId()).thenReturn(id);
        when(kb.getName()).thenReturn(name);
        when(kb.getDescription()).thenReturn(name + " 的描述");
        when(kb.isArchived()).thenReturn(false);
        return kb;
    }

    private KnowledgeBaseMember membership(long kbId) {
        KnowledgeBaseMember member = mock(KnowledgeBaseMember.class);
        when(member.getKbId()).thenReturn(kbId);
        return member;
    }

    // ---------- OAuth 流程辅助 ----------

    private record Pkce(String verifier, String challenge) {
    }

    private static Pkce newPkce() throws Exception {
        byte[] raw = new byte[48];
        new SecureRandom().nextBytes(raw);
        String verifier = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(verifier.getBytes(StandardCharsets.US_ASCII));
        return new Pkce(verifier, Base64.getUrlEncoder().withoutPadding().encodeToString(digest));
    }

    /** 注册机密客户端并完成授权码+PKCE 换取不透明访问令牌。 */
    private record ClientAndToken(String clientId, String clientSecret,
                                  String accessToken, String refreshToken) {
    }

    private ClientAndToken authorizeAs(String username) throws Exception {
        String[] registered = registerClient();
        String clientId = registered[0];
        String clientSecret = registered[1];
        String basic = "Basic " + java.util.Base64.getEncoder()
                .encodeToString((clientId + ":" + clientSecret).getBytes(StandardCharsets.UTF_8));
        Pkce pkce = newPkce();
        MockHttpSession session = new MockHttpSession();

        // 1. 匿名访问授权端点 → 重定向到登录页（浏览器语义）。
        MvcResult authorize = mockMvc.perform(get("/oauth2/authorize")
                        .session(session)
                        .accept(MediaType.TEXT_HTML)
                        .queryParam("response_type", "code")
                        .queryParam("client_id", clientId)
                        .queryParam("redirect_uri", REDIRECT_URI)
                        .queryParam("scope", McpActor.SEARCH_SCOPE)
                        .queryParam("state", "st-123")
                        .queryParam("code_challenge", pkce.challenge())
                        .queryParam("code_challenge_method", "S256"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location",
                        org.hamcrest.Matchers.containsString("/login")))
                .andReturn();

        // 2. 登录页提供 CSRF 令牌；表单登录后回到被保存的授权请求。
        MvcResult loginPage = mockMvc.perform(get(pathOf(loginPageLocation(authorize)))
                        .session(session)
                        .accept(MediaType.TEXT_HTML))
                .andExpect(status().isOk())
                .andReturn();
        String csrf = extractCsrf(loginPage.getResponse().getContentAsString());
        assertThat(csrf).isNotBlank();

        MvcResult login = mockMvc.perform(post("/login")
                        .session(session)
                        .accept(MediaType.TEXT_HTML)
                        .param("username", username)
                        .param("password", username + "-pass")
                        .param("_csrf", csrf))
                .andExpect(status().is3xxRedirection())
                .andReturn();
        String savedAuthorize = pathOf(login.getResponse().getHeader("Location"));

        // 3. 已认证会话重新进入授权端点 → 302 回 redirect_uri 并携带授权码。
        MvcResult granted = mockMvc.perform(get(savedAuthorize).session(session)
                        .accept(MediaType.TEXT_HTML))
                .andExpect(status().is3xxRedirection())
                .andReturn();
        URI callback = URI.create(granted.getResponse().getHeader("Location"));
        assertThat(callback.getHost()).isEqualTo("127.0.0.1");
        String code = java.util.Arrays.stream(callback.getQuery().split("&"))
                .filter(pair -> pair.startsWith("code="))
                .findFirst().orElseThrow()
                .substring(5);
        String state = java.util.Arrays.stream(callback.getQuery().split("&"))
                .filter(pair -> pair.startsWith("state="))
                .findFirst().orElseThrow()
                .substring(6);
        assertThat(state).isEqualTo("st-123");

        // 4. 授权码 + PKCE 校验器换取访问令牌（公共客户端，无密钥）。
        MvcResult token = mockMvc.perform(post("/oauth2/token")
                        .header("Authorization", basic)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("grant_type", "authorization_code")
                        .param("code", code)
                        .param("redirect_uri", REDIRECT_URI)
                        .param("client_id", clientId)
                        .param("code_verifier", pkce.verifier()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token_type").value("Bearer"))
                .andExpect(jsonPath("$.scope").value(McpActor.SEARCH_SCOPE))
                .andDo(org.springframework.test.web.servlet.result.MockMvcResultHandlers.print())
                .andReturn();
        JsonNode body = JSON.readTree(token.getResponse().getContentAsString());
        return new ClientAndToken(clientId, clientSecret,
                body.get("access_token").asText(),
                body.has("refresh_token") ? body.get("refresh_token").asText() : null);
    }

    private String[] registerClient() throws Exception {
        MvcResult result = mockMvc.perform(post("/connect/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"redirect_uris":["%s"],"client_name":"集成测试 MCP 客户端"}
                                """.formatted(REDIRECT_URI)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.client_secret").isNotEmpty())
                .andReturn();
        JsonNode registration = JSON.readTree(result.getResponse().getContentAsString());
        return new String[]{
                registration.get("client_id").asText(),
                registration.get("client_secret").asText()};
    }

    private static String loginPageLocation(MvcResult authorizeRedirect) {
        return authorizeRedirect.getResponse().getHeader("Location");
    }

    /** 重定向 Location 可能是绝对 URL；MockMvc 只接受 path?query。 */
    private static String pathOf(String location) {
        URI uri = URI.create(location);
        if (uri.getHost() == null) {
            return location;
        }
        return uri.getPath() + (uri.getQuery() == null ? "" : "?" + uri.getQuery());
    }

    private static String extractCsrf(String html) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("name=\"_csrf\" value=\"([^\"]+)\"")
                .matcher(html);
        return matcher.find() ? matcher.group(1) : "";
    }

    private String jsonRpc(String session, String token, String body) throws Exception {
        MvcResult result = performRpc(session, token, body);
        return result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    }

    /** Streamable HTTP 的响应可能是同步 JSON，也可能是异步 SSE；两者都要读完。 */
    private MvcResult performRpc(String session, String token, String body) throws Exception {
        MvcResult started = mockMvc.perform(post("/mcp")
                        .header("Authorization", "Bearer " + token)
                        .header("Mcp-Session-Id", session)
                        .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();
        return started.getRequest().isAsyncStarted()
                ? mockMvc.perform(asyncDispatch(started)).andReturn()
                : started;
    }

    // ---------- 测试用例 ----------

    @Test
    void protectedResourceMetadataIsDiscoverableAnonymously() throws Exception {
        mockMvc.perform(get("/.well-known/oauth-protected-resource/mcp"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resource").value("http://kwiki.test/mcp"))
                .andExpect(jsonPath("$.authorization_servers[0]").value("http://kwiki.test"))
                .andExpect(jsonPath("$.scopes_supported[0]").value("mcp:search"));
    }

    @Test
    void unauthenticatedMcpRequestGetsBearerChallengeWithResourceMetadata() throws Exception {
        mockMvc.perform(post("/mcp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
                        .content("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\","
                                + "\"params\":{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},"
                                + "\"clientInfo\":{\"name\":\"it\",\"version\":\"0\"}}}"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate",
                        org.hamcrest.Matchers.containsString("resource_metadata="
                                + "\"http://kwiki.test/.well-known/oauth-protected-resource/mcp\"")));
    }

    @Test
    void unknownTokenIsRejectedAsUnauthorized() throws Exception {
        mockMvc.perform(post("/mcp")
                        .header("Authorization", "Bearer not-a-issued-token")
                        .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void endToEndAuthorizedClientCanListAndSearchWithinScope() throws Exception {
        ClientAndToken session1 = authorizeAs("alice");

        // initialize → tools/list → tools/call（受保护资源）。
        MvcResult initialize = mockMvc.perform(post("/mcp")
                        .header("Authorization", "Bearer " + session1.accessToken())
                        .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"jsonrpc":"2.0","id":1,"method":"initialize",
                                 "params":{"protocolVersion":"2025-06-18","capabilities":{},
                                           "clientInfo":{"name":"it","version":"0"}}}
                                """))
                .andExpect(status().isOk())
                .andDo(org.springframework.test.web.servlet.result.MockMvcResultHandlers.print())
                .andReturn();
        JsonNode initResult = readRpc(initialize.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(initResult.get("result").get("serverInfo").get("name").asText())
                .isEqualTo("kwiki-mcp");
        String sessionId = initialize.getResponse().getHeader("Mcp-Session-Id");

        mockMvc.perform(post("/mcp")
                        .header("Authorization", "Bearer " + session1.accessToken())
                        .header("Mcp-Session-Id", sessionId)
                        .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}"))
                .andExpect(status().isAccepted());

        JsonNode tools = readRpc(jsonRpc(sessionId, session1.accessToken(),
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}"));
        assertThat(tools.get("result").get("tools").toString())
                .contains("list_knowledge_bases")
                .contains("search");

        // 目录工具：只含 alice 是成员的 101/102。
        JsonNode listing = readRpc(jsonRpc(sessionId, session1.accessToken(),
                "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\","
                        + "\"params\":{\"name\":\"list_knowledge_bases\",\"arguments\":{}}}"))
                .get("result");
        assertThat(listing.get("isError").asBoolean()).isFalse();
        assertThat(listing.get("structuredContent").get("total").asInt()).isEqualTo(2);
        assertThat(listing.get("structuredContent").toString())
                .contains("工程手册")
                .doesNotContain("机密库");

        // 检索工具：默认全量作用域；指定 kbIds 收窄。
        JsonNode searchAll = readRpc(jsonRpc(sessionId, session1.accessToken(),
                "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/call\",\"params\":"
                        + "{\"name\":\"search\",\"arguments\":{\"query\":\"部署流程\"}}}"))
                .get("result");
        assertThat(searchAll.get("isError").asBoolean()).isFalse();
        assertThat(searchAll.get("structuredContent").get("hitCount").asInt()).isEqualTo(1);
        assertThat(searchAll.get("structuredContent").get("hits").get(0).get("kbName").asText())
                .isEqualTo("工程手册");
    }

    @Test
    void searchOutsideMembershipIsDeniedPerKbId() throws Exception {
        ClientAndToken session1 = authorizeAs("alice");
        String sessionId = initialize(session1.accessToken());

        JsonNode denied = readRpc(jsonRpc(sessionId, session1.accessToken(),
                "{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"tools/call\",\"params\":"
                        + "{\"name\":\"search\",\"arguments\":{\"query\":\"薪资等级\",\"kb_ids\":[103]}}}"))
                .get("result");
        assertThat(denied.get("isError").asBoolean()).isTrue();
        assertThat(denied.get("structuredContent").get("error").asText())
                .isEqualTo("knowledge_base_denied");
        assertThat(denied.get("structuredContent").get("message").asText()).contains("103");
    }

    @Test
    void userWithoutMembershipCannotSeeAnyKnowledgeBase() throws Exception {
        ClientAndToken bob = authorizeAs("bob");
        String sessionId = initialize(bob.accessToken());

        JsonNode listing = readRpc(jsonRpc(sessionId, bob.accessToken(),
                "{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"tools/call\","
                        + "\"params\":{\"name\":\"list_knowledge_bases\",\"arguments\":{}}}"))
                .get("result");
        assertThat(listing.get("structuredContent").get("total").asInt()).isZero();

        JsonNode search = readRpc(jsonRpc(sessionId, bob.accessToken(),
                "{\"jsonrpc\":\"2.0\",\"id\":6,\"method\":\"tools/call\",\"params\":"
                        + "{\"name\":\"search\",\"arguments\":{\"query\":\"anything\",\"kb_ids\":[101]}}}"))
                .get("result");
        assertThat(search.get("isError").asBoolean()).isTrue();
    }

    @Test
    void revokedAccessTokenStopsWorkingImmediately() throws Exception {
        ClientAndToken alice = authorizeAs("alice");
        String sessionId = initialize(alice.accessToken());
        assertThat(readRpc(jsonRpc(sessionId, alice.accessToken(),
                "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"tools/list\"}"))
                .has("result")).isTrue();

        mockMvc.perform(post("/oauth2/revoke")
                        .header("Authorization", "Basic " + java.util.Base64.getEncoder()
                                .encodeToString((alice.clientId() + ":" + alice.clientSecret())
                                        .getBytes(StandardCharsets.UTF_8)))
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("token", alice.accessToken()))
                .andExpect(status().isOk());

        mockMvc.perform(post("/mcp")
                        .header("Authorization", "Bearer " + alice.accessToken())
                        .header("Mcp-Session-Id", sessionId)
                        .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jsonrpc\":\"2.0\",\"id\":8,\"method\":\"tools/list\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void refreshTokenRotationIssuesFreshAccessToken() throws Exception {
        ClientAndToken alice = authorizeAs("alice");
        assertThat(alice.refreshToken()).isNotBlank();

        String basic = "Basic " + java.util.Base64.getEncoder()
                .encodeToString((alice.clientId() + ":" + alice.clientSecret())
                        .getBytes(StandardCharsets.UTF_8));

        MvcResult refresh = mockMvc.perform(post("/oauth2/token")
                        .header("Authorization", basic)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .accept(MediaType.APPLICATION_JSON)
                        .param("grant_type", "refresh_token")
                        .param("refresh_token", alice.refreshToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.access_token").isNotEmpty())
                .andExpect(jsonPath("$.refresh_token").isNotEmpty())
                .andReturn();
        JsonNode rotated = JSON.readTree(refresh.getResponse().getContentAsString());
        String freshToken = rotated.get("access_token").asText();
        String freshRefresh = rotated.get("refresh_token").asText();
        assertThat(freshToken).isNotEqualTo(alice.accessToken());
        assertThat(freshRefresh).isNotEqualTo(alice.refreshToken());

        // 新访问令牌可用（重新 initialize）；旧刷新令牌已被轮换作废（重放被拒）。
        mockMvc.perform(post("/mcp")
                        .header("Authorization", "Bearer " + freshToken)
                        .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"jsonrpc":"2.0","id":1,"method":"initialize",
                                 "params":{"protocolVersion":"2025-06-18","capabilities":{},
                                           "clientInfo":{"name":"it","version":"0"}}}
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(post("/oauth2/token")
                        .header("Authorization", basic)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .accept(MediaType.APPLICATION_JSON)
                        .param("grant_type", "refresh_token")
                        .param("refresh_token", alice.refreshToken()))
                .andExpect(status().isBadRequest());
    }

    private String initialize(String accessToken) throws Exception {
        MvcResult result = mockMvc.perform(post("/mcp")
                        .header("Authorization", "Bearer " + accessToken)
                        .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"jsonrpc":"2.0","id":1,"method":"initialize",
                                 "params":{"protocolVersion":"2025-06-18","capabilities":{},
                                           "clientInfo":{"name":"it","version":"0"}}}
                                """))
                .andExpect(status().isOk())
                .andReturn();
        return result.getResponse().getHeader("Mcp-Session-Id");
    }

    /** 解析 JSON 或 SSE（data: 行）形式的 JSON-RPC 响应。 */
    private static JsonNode readRpc(String body) throws Exception {
        String payload = body;
        if (payload.contains("data:")) {
            // SSE 按 \n 分帧（不能用 \\R：ISO-8859-1 误解码的 0x85 会被当作换行），
            // 一个事件的 JSON 可能拆成多个 data: 行，需按序拼接。
            StringBuilder joined = new StringBuilder();
            for (String line : payload.split("\n", -1)) {
                String trimmed = line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
                if (trimmed.startsWith("data:")) {
                    joined.append(trimmed.substring(5).stripLeading());
                }
            }
            payload = joined.toString();
        }
        return JSON.readTree(payload);
    }
}
