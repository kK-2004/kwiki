# kwiki 项目长期记忆

## 项目结构
- Java(Spring Boot 3) + Vue3 知识库/wiki 系统，含 RAG 检索问答链路。
- 后端包结构：`com.kwiki.{wiki,rag,indexing,infrastructure,security}`，
  另有 `src/test/java/com/kwiki/{audit,architecture}` 做仓库级审计测试。

## 约定
- **注释统一使用简体中文**（2026-09-10 全仓库首次中文化，315 个文件；
  2026-09-21 补齐 22 个文件的 51 行）。
  保留英文的仅有：`TODO`/`FIXME`/`NOTE` 标记、代码样例与语法示例、
  技术标识符（类名/表名/配置 key/`{@code}`/`{@link}`/缩写如 RRF、BM25、SSE、DTO）、
  注释内的 HTML 结构标签（`<pre>`/`<ul>`/`<ol>`）、魔法字节标注、
  被引号引用的英文报错原文。
- **约定文件：仓库根目录 `AGENT.md`**（2026-09-21 创建），集中写明了
  注释语言规则、例外清单、Flyway 迁移只读、无 Docker、密钥、验收命令。
- **Flyway 迁移文件禁止改动（含注释）**：`src/main/resources/db/migration/*.sql`
  参与 checksum 校验，改注释会导致已部署环境校验失败。批量注释任务必须跳过该目录。
- 构建必须**无 Docker**：不引入 Testcontainers / docker-compose，
  中间件一律由运维方提供（`KWIKI_*` 环境变量）。
- 密钥永不落库到仓库：`.env.example` 与 `application*.yml` 只放 `${...}` 占位符。

## 前端
- 两个前端：`frontend/`（主站）与 `admin-frontend/`（后台）。
- `pnpm` 不在 PATH 上，用 `node_modules/.bin/` 下的二进制跑：
  `./node_modules/.bin/vue-tsc --noEmit`、`./node_modules/.bin/vitest run`。

## 已知问题
- `CommonResponseContractTest` 等基于 `@SpringBootTest` 的用例因
  `src/test/java/com/kwiki/testutil/WikiMockBeans.java` 未提供
  `WikiPageDraftRepository` bean 而上下文加载失败（自 HEAD 起就存在）。
- `NoMinioCouplingAuditTest.pomDeclaresOnlyTheContentCenterStorageDependency`
  仍硬编码 `<content-center-sdk.version>0.1.3</content-center-sdk.version>`，
  而 pom.xml 已升到 `0.1.4` → 该审计用例失败（属版本升级遗留，与注释无关）。
