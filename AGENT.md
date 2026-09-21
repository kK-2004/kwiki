# AGENT.md

本文件约束在本仓库工作的 AI 代理与协作者。**改动代码前请先读这里。**

---

## 1. 注释一律使用简体中文（强制）

本项目所有源码与配置的注释使用**简体中文**。新增或修改注释时不得留英文散文。

适用文件类型：`.java`、`.ts`、`.js`、`.mjs`、`.vue`、`.css`、`.sql`、`.sh`、
`.yml`、`.yaml`、`.html`、`.xml`、`.gitignore`。

### 1.1 保留英文的例外（**不要翻译**）

| 类别 | 示例 |
|---|---|
| 技术标识符、类名/表名/字段名/配置 key | `search_index_change_event`、`resourceType:resourceId:revisionId:operation` |
| 通用缩写 | `RRF`、`BM25`、`SSE`、`DTO`、`CTA`、`CGNAT`、`IPv6 ULA` |
| 工具/协议/产品名 | Elasticsearch、Flyway、Playwright、Vite、Redis、PDF.js |
| `TODO` / `FIXME` / `NOTE` 等标记 | `// TODO: 补齐分页` |
| `{@code ...}` / `{@link ...}` 内的内容 | `{@code indexing_job_target}` |
| 语法/命令行示例 | `scripts/validate-migrations.sh`、`env KWIKI_X=1 mvnw ...` |

### 1.2 禁止翻译的「伪注释」

下面这些形态会被扫描工具当成注释，但它们是**代码或标记**，必须原样保留：

- 类型引用指令：`/// <reference types="vite/client" />`
- 注释内的代码样例：`<img src="..." width="640" />`、`![alt](url)`、`<audio src="..">`
- 注释内的结构标签：`<pre>`、`</pre>`、`<ul>`、`<ol>`、`<li>`、`</li>`
- 协议标记与魔法字节：`&lt;&lt;KWIKI_META_DATA_START ...&gt;&gt;`、`WAV：RIFF....WAVE`
- 表达式的注释尾巴：`// 16 px < MIN_PIXELS`、`// 8 + 1 > max 8`
- 被引号引用的英文原文（**报错信息、日志、配置项名不得意译**）：
  `// 正式启动报 "Unable to retrieve @EnableAutoConfiguration base packages"。`
- 分隔标记：`.gitignore` 里的 `# ---- IDE ----`（JetBrains 会重写该段）
- shebang：`#!/usr/bin/env bash`

### 1.3 判定标准

一条注释「算不算需要翻译」，看**它是不是给人读的说明文字**。
是说明文字 → 翻成中文；是代码/标识符/标记 → 原样保留。

半中半英也要修：「中文句子里夹着一串完整的英文句子」属于漏译，
但「中文句子里夹着技术名词」（如 `Query Rewrite Agent 的结构化反馈输入`）是正常的。

---

## 2. Flyway 迁移文件：**禁止改动**（包括注释）

`src/main/resources/db/migration/` 下的所有 `.sql` 文件是**只读**的。

Flyway 按文件内容计算 checksum。改动注释同样会改变 checksum，
导致**已部署环境启动时校验失败**，生产库会被卡住。

> 因此「把所有注释改成中文」这类批量任务**必须跳过该目录**。
> 需要修正历史迁移里的注释时，只能新增一个迁移版本去说明，不要回改旧文件。

同理，`tools/validate-migrations*`、`scripts/validate-migrations.sh` 校验的是
文件名与版本号，修改它们前先确认不会放宽校验规则。

---

## 3. 构建：不依赖 Docker

本项目**不使用 Docker**，也不要引入 Testcontainers、docker-compose 等需要容器的测试设施。
中间件（MySQL / Redis / Elasticsearch / 对象存储）一律由运维方提供，
通过 `KWIKI_*` 环境变量注入。

`scripts/check-no-docker-middleware.sh` 与 `NoDockerMiddlewareAuditTest`
会扫描源码与构建文件，**不要引入被禁的中间件耦合**。

---

## 4. 密钥永不落库

- `.env.example` 与 `application*.yml` 只放 `${...}` 占位符，绝不写真实密钥、口令、Token。
- 提交前自查：`git diff --cached` 里不应出现任何真实凭据。
- `RepositorySecretsAuditTest` 会扫描仓库文本，命中即失败。

---

## 5. 改完之后的验收

```bash
./mvnw -o -q compile
./mvnw -o -q test-compile
./mvnw -o test -Dtest='*AuditTest,ArchitectureRulesTest' -DfailIfNoSpecifiedTests=false

cd frontend        && pnpm typecheck && pnpm test
cd admin-frontend  && pnpm typecheck && pnpm test
```

批量改注释时，必须能证明「只动了注释」：把注释正文替换成占位符后，
改动前后的文件应逐字符一致；`mvnw compile` 与类型检查必须仍然通过。

---

## 6. 已知既有问题（不是你的改动造成的）

- `CommonResponseContractTest` 等 `@SpringBootTest` 用例会因
  `src/test/java/com/kwiki/testutil/WikiMockBeans.java` 未提供
  `WikiPageDraftRepository` bean 而上下文加载失败，该问题自 `HEAD` 起就存在。
- 排查这类失败先看 `target/surefire-reports/*.txt` 的 `Caused by`，
  再用 `git show HEAD:<file>` 判断是不是既有问题，不要算到本次改动头上。
