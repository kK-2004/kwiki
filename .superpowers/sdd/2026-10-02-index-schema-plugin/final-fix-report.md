# 最终修复报告

日期：2026-10-02。基线：`e2e981a`。本报告随最终修复提交；提交名为 `fix: 修复灰度默认配置与严格映射校验`，提交号见该提交及交付消息。

## 修复范围

- `GrayReleaseCreateDialog` 的解析器默认值与字段格式、分块器一致，优先采用全局线上版本；没有全局线上版本时使用目录字段格式默认值和首个可用解析器/分块器。
- `MappingValidator` 的强类型与 Map 两个入口均要求 `dynamic: strict`；Map 入口同时要求 `vector.type=dense_vector`。维度、kNN 索引和插件必需字段检查保留。
- 受影响的映射测试夹具显式声明 strict；不放宽校验或断言。

## 测试先行

1. 写入回归后运行 `./mvnw -o -q test -Dtest=MappingValidatorTest`：退出 1；3 项中 2 项失败，非 strict 和错误 Map 向量类型均被原实现接受。日志：`/tmp/schema-mapping-red.log`。
2. 写入 UI 回归后运行 `pnpm test -- tests/gray-release.spec.ts`（管理端）：退出 1；Vitest 实际执行全部 8 文件，27 项中 2 项失败，线上 parse-1 在 parse-2 可用与不可用时均未被选中。日志：`/tmp/schema-gray-red.log`。

## 最终验证

所有命令在指定 schema 工作目录执行，管理端命令在其 `admin-frontend` 目录执行。

| 命令 | 结果与输出摘要 | 日志 |
| --- | --- | --- |
| `./mvnw -o -q test -Dtest='MappingValidatorTest,ChunkMappingTest,MultimodalChunkDocumentTest,ElasticsearchIndexBootstrapTest,ElasticsearchIndexManagerTest,SearchIndexValidationServiceTest'` | 退出 0；6 类、37 项，0 失败/错误/跳过（Surefire XML 计数） | `/tmp/schema-focused.log` |
| `./mvnw -o -q compile` | 退出 0；无错误输出 | `/tmp/schema-compile.log` |
| `./mvnw -o -q test-compile` | 退出 0；无错误输出 | `/tmp/schema-test-compile.log` |
| `./mvnw -o test` | 退出 0；仅运行一次最终全量；168 类；`Tests run: 925, Failures: 0, Errors: 0, Skipped: 44`；`BUILD SUCCESS`，用时 1 分钟 | `/tmp/schema-full-backend.log` |
| `pnpm typecheck` | 退出 0；`vue-tsc --noEmit` | `/tmp/schema-admin-typecheck.log` |
| `pnpm test` | 退出 0；`Test Files 8 passed (8)`；`Tests 28 passed (28)` | `/tmp/schema-admin-test.log` |
| `pnpm build` | 退出 0；1469 模块，`built in 2.21s`；已有 bundle 大于 500 kB 提示 | `/tmp/schema-admin-build.log` |
| `git diff --check` | 退出 0；无输出 | 提交前执行 |

全量测试运行中有 Nacos 本地服务连接失败日志，最终测试结果仍为成功，未修改该服务或测试设施。

## 覆盖与自查

- UI 同时覆盖线上 parse-1 / parse-2 可用、线上 parse-1 / parse-2 不可用两种情况，确认 parse-1 被选中并实际提交。
- online-schema/online-chunker 与 catalog-schema/catalog-chunker 不同，断言三个默认值取线上；无全局线上版本（仅有知识库 scoped 版本）时断言目录默认值。
- 点击真实 Element Plus 下拉选项并再次提交，断言请求实际携带 catalog-schema/catalog-chunker；同一 UI 测试断言停用字段格式/分块器 `aria-disabled=true` 与解析器 disabled。
- 两个 MappingValidator 入口覆盖 dynamic true、false、缺失拒绝；Map 额外覆盖字符串 true/false。两个入口覆盖错误 vector 类型拒绝、完整 strict 映射通过。已有维度、必需字段和非 indexed 回归保留。
- 改动限定为两处目标生产代码、对应管理端测试与 3 个映射测试文件、此报告。没有改历史 SQL、COMMUNITY 或非管理端 frontend 源码；没有新增密钥、英文说明注释、Docker 耦合或 CoAuthored。
- 工作区基线干净；提交前审查 `git diff` 与 `git diff --check`，未发现额外范围变更。提交后应为干净工作区。
