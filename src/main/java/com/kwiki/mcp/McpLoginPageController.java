package com.kwiki.mcp;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * 授权服务器登录页：服务端渲染的最小表单，CSRF 令牌来自请求属性，
 * 凭据由 formLogin POST 到 /login 并复用既有的
 * {@code DatabaseUserDetailsService}。成功后回到原始授权请求；
 * 失败仅提示通用错误，不区分账号是否存在。页面不缓存。
 */
@Controller
@ConditionalOnProperty(prefix = "kwiki.mcp", name = "enabled", havingValue = "true")
public class McpLoginPageController {

    @GetMapping(value = "/login", produces = MediaType_UTF8)
    @ResponseBody
    public String loginPage(@RequestParam(value = "error", required = false) String error,
                            HttpServletRequest request) {
        CsrfToken csrf = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        String token = csrf == null ? "" : csrf.getToken();
        String errorHtml = error != null
                ? "<p class=\"error\">用户名或密码不正确。</p>"
                : "";
        return """
                <!DOCTYPE html>
                <html lang="zh-CN">
                <head>
                  <meta charset="utf-8">
                  <meta name="viewport" content="width=device-width, initial-scale=1">
                  <title>登录 kwiki · MCP 授权</title>
                  <style>
                    body { font-family: system-ui, sans-serif; background: #f5f6f8;
                           display: flex; justify-content: center; padding-top: 8vh; }
                    main { background: #fff; border: 1px solid #e2e5ea; border-radius: 10px;
                           padding: 2rem 2.5rem; width: 22rem; }
                    h1 { font-size: 1.1rem; margin: 0 0 .25rem; }
                    p.hint { color: #5b6470; font-size: .85rem; margin-top: 0; }
                    label { display: block; font-size: .8rem; color: #333; margin: .9rem 0 .25rem; }
                    input[type=text], input[type=password] { width: 100%%; box-sizing: border-box;
                           padding: .5rem; border: 1px solid #c9ced6; border-radius: 6px; }
                    button { margin-top: 1.2rem; width: 100%%; padding: .55rem;
                             background: #2563eb; border: 0; border-radius: 6px;
                             color: #fff; font-size: .95rem; cursor: pointer; }
                    button:hover { background: #1d4fd7; }
                    p.error { color: #b42318; font-size: .85rem; }
                  </style>
                </head>
                <body>
                <main>
                  <h1>登录 kwiki</h1>
                  <p class="hint">一个 MCP 客户端正在请求访问你有权限的知识库检索。
                  请使用 kwiki 账号登录以继续。</p>
                  %s
                  <form method="post" action="/login">
                    <input type="hidden" name="_csrf" value="%s">
                    <label for="username">用户名</label>
                    <input type="text" id="username" name="username" autocomplete="username" required autofocus>
                    <label for="password">密码</label>
                    <input type="password" id="password" name="password" autocomplete="current-password" required>
                    <button type="submit">登录并授权</button>
                  </form>
                </main>
                </body>
                </html>
                """.formatted(errorHtml, escape(token));
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;").replace("\"", "&quot;")
                .replace("<", "&lt;").replace(">", "&gt;");
    }

    private static final String MediaType_UTF8 = "text/html;charset=UTF-8";
}
