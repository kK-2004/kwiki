# 首次部署可并发执行的索引配置代与管理检查失败契约（任务 1.4）

## 可并发执行的配置代

首次部署（以及任何一次运行中的应用实例）能够同时执行的结构配置代 = `kwiki.indexing.manifests`
声明的清单 ∪ 隐式默认代：

1. **隐式默认代**（`manifests` 留空时唯一受支持代）：
   - parser `kwiki-parse-1`、chunker `kwiki-chunk-1`
   - embedding 档案 `default` → `kwiki.qwen-embedding.*`（baseUrl/apiKey/timeout）
   - model/dimensions 取部署环境实际生效值（如 `text-embedding-v4`/1024 或
     local profile 的 `qwen3.7-text-embedding`/1024）
   - mappingSchemaVersion 1（`ChunkMappingBuilder` 当前字段集，参考哈希见 notes/v1-baseline.md）
2. **显式清单代**：`kwiki.indexing.manifests[i]` 每项声明一个代，凭据经
   `kwiki.indexing.embeddings.<profile>` 解析；`default` 档案未显式配置时回退到
   `kwiki.qwen-embedding.*`。

"并发执行"指双写/重建场景：同一资源事件同时写入多个物理版本时，每个目标版本按其
built configuration 对应的代走各自的 parser/chunker/embedding 流水线。**只有当所有
参与的代都出现在受支持清单中且凭据可解析时**，首次部署才能并发执行它们；否则对应
目标在入队前就会被拒绝（见下）。

示例——v1(1024 维) 与 v2(2048 维) 双写所需的最小配置：

```yaml
kwiki:
  indexing:
    manifests:
      - id: gen-v1-1024
        parser-version: kwiki-parse-1
        chunker-version: kwiki-chunk-1
        embedding-profile: default
        embedding-model: text-embedding-v4
        dimensions: 1024
        mapping-schema-version: 1
      - id: gen-v2-2048
        parser-version: kwiki-parse-1
        chunker-version: kwiki-chunk-1
        embedding-profile: qwen-v4-dashscope
        embedding-model: text-embedding-v4
        dimensions: 2048
        mapping-schema-version: 1
    embeddings:
      qwen-v4-dashscope:
        base-url: https://dashscope.aliyuncs.com/compatible-mode/v1
        api-key: ${KWIKI_QWEN_V4_2048_API_KEY}
```

## 不受支持版本的管理检查失败契约

以下时点执行"受支持性检查"，命中失败时**管理检查失败关闭**，绝不降级为继续执行：

| 时点 | 检查对象 | 失败动作 |
| --- | --- | --- |
| 启动引导/对账（任务 3.5/3.6/8.5） | selected 或 writeEnabled 版本的 built 配置 | 版本标记 `UNSUPPORTED`（健康摘要），管理端列出缺失的流水线/凭据；读路径照常经别名，不中断 |
| 管理端创建/编辑配置（任务 6.1） | 请求的 parser/chunker/model/dimensions/mapping 组合 | 拒绝：`unsupported configuration`（可执行的非密钥错误），不产生半成品版本 |
| 重建前预检（任务 6.2/6.3） | 目标版本 built/请求配置 | 拒绝启动 run |
| 切换准备/选择（任务 6.6/8.3） | 目标及全部将变为 writeEnabled 的版本 | 阻止切换到不受支持版本；受支持性变化会使既有 READY 校验失效（任务 7.5） |
| 入队扇出（任务 4.3） | 每个写目标版本的代 | 该版本目标标记失败/落后，不影响其他目标（任务 4.6/5.5） |

判定规则：版本 built 配置六元组
`(parserVersion, chunkerVersion, embeddingProfile 等价凭据, embeddingModel, dimensions, mappingSchemaVersion)`
必须与某受支持清单项**精确匹配**；embedding 档案必须解析出非空 baseUrl/apiKey。清单热更新
（发版后新增代）不会自动作用于已 selected/writeEnabled 的旧版本：旧版本只要仍出现在清单中
就继续受支持；从清单移除即视为收回支持（版本转 UNSUPPORTED，禁止继续双写声明与切换）。
