# kwiki 项目长期记忆

## 项目结构
- Java(Spring Boot 3) + Vue3 知识库/wiki 系统，含 RAG 检索问答链路。
- 后端包结构：`com.kwiki.{wiki,rag,indexing,infrastructure,security}`，
  另有 `src/test/java/com/kwiki/{audit,architecture}` 做仓库级审计测试。

## 约定
- **注释统一使用简体中文**（2026-09-10 全仓库完成中文化，315 个文件）。
  保留英文的仅有：`TODO`/`FIXME`/`NOTE` 标记、代码样例与语法示例、
  技术标识符（类名/表名/配置 key/`{@code}`/`{@link}`/缩写如 RRF、BM25、SSE、DTO）。
- 构建必须**无 Docker**：不引入 Testcontainers / docker-compose，
  中间件一律由运维方提供（`KWIKI_*` 环境变量）。
- 密钥永不落库到仓库：`.env.example` 与 `application*.yml` 只放 `${...}` 占位符。

## 已知问题
- `CommonResponseContractTest` 等基于 `@SpringBootTest` 的用例因
  `src/test/java/com/kwiki/testutil/WikiMockBeans.java` 未提供
  `WikiPageDraftRepository` bean 而上下文加载失败（自 HEAD 起就存在）。
