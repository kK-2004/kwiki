# 索引插件开发与上线说明

CHUNK 流水线通过 Spring Bean 注册三类插件，当前没有外部 JAR 动态加载、
运行时上传或热注册机制。新增实现需随应用部署；只增加清单 id 不会增加执行器。

## 接口与注册

三类接口都继承 `IndexPlugin`：提供 `id()`、管理端 `label()` 和
`availability()`；不可用原因须脱敏。id 必须符合
`^[a-z0-9][a-z0-9-]{1,62}$`，同类注册表拒绝重复 id；已被版本使用的 id
不得改变语义，行为或字段变化应新增 id 与实现。注册表按 id 排序返回目录。

| 类型 | 接口 | 注册表 | 实现责任 |
| --- | --- | --- | --- |
| 字段格式 | `ChunkIndexSchema` | `ChunkIndexSchemaRegistry` | `mapping(dimensions)`、`requiredFields()`、父子块装饰、`capabilities()`；注册表统一计算映射摘要 |
| 解析器 | `DocumentParserPlugin` | `DocumentParserRegistry` | `requiredCapabilities()`、小写非图片附件 MIME 集合、`parsePage` / `parseAttachment` / `parseImport` |
| 分块器 | `ChunkerPlugin` | `ChunkerRegistry` | `parents(keyPrefix, StructuredDocument)` 与 `children(ParentChunk, StructuredDocument)` |

实现类通过 `@Component` 或 `@Bean` 注册，注册表自动收集对应接口的 Bean。
图片附件仍走公共元数据路径；解析请求中的附件 Supplier 按需读取字节。
字段格式必须提供解析器要求的全部 `IndexCapability`；当前能力包括
`RESOURCE_IDS` 与 `ENTITY_LINKING`，图构建另外要求后者。能力不能从 id
编号推断。分块器接口没有独立的能力需求集合。

内置分块器 `KwikiChunkV1`（`kwiki-chunk-1`）封装原有默认配置的
`ParentChunker` 与 `ChildChunker`；父子块的内容、key、序号、字符区间、边界及父块标题路径保持原算法，
受保护标记处理不变。`kwiki-schema-1` 仍从父子块原文提取 contentIds，
子块仍添加实体关联字段，只有子块承载向量。分块器由目标版本配置解析，
不是固定适配器。向量缓存身份包含解析器与分块器，防止不同处理结果共用缓存。
CHUNK 字段格式使用字符串 id；COMMUNITY 仍使用独立数字映射版本。

## 默认配置与清单

```yaml
kwiki:
  indexing:
    defaults:
      index-schema: kwiki-schema-1
      parsers: [kwiki-parse-2, kwiki-parse-1]
      chunker: kwiki-chunk-1
```

默认 schema、chunker 及全部 parser id 必须已注册；parser 列表非空，最后一项
必须可用。首次部署管理写操作关闭时使用最后一项作为基线；开启时按偏好
顺序选取首个可执行 parser，固定默认 schema、默认 chunker 和部署向量配置，
并验证完整组合。无匹配组合时失败关闭，不能用其他分块器清单替代默认分块器。
修改默认值不会改写已有版本或物理索引；新建版本和灰度可显式选择插件。

`kwiki.indexing.manifests` 非空时仅支持显式清单，不补充默认组合；每项登记
parser-version、chunker-version、index-schema、embedding-profile、
embedding-model、dimensions。执行还要求插件可用、能力兼容、向量档案可解析
且模型与维度匹配。清单为空时，系统遍历全部可用 schema、parser、chunker，
按 parser 能力需求筛选组合，向量使用部署的默认档案。

`kwiki-parse-2` 始终注册，但可用性取决于多模态开关、视觉配置和服务装配。
PDF 导入文本通过注册表选择已切换的知识库灰度 parser，否则选择全局选中
parser；无版本时回退 `kwiki-parse-1`。导入选择不单独验证完整分块或向量
流水线，不能把导入文本可解析当作索引就绪证明；目标版本需通过完整流水线
可执行性校验。PDF 图片语义仍在导入页发布后的索引任务处理。

## 上线与验证

1. 新增稳定 id 的实现并注册 Spring Bean，部署应用；核查目录、可用性原因、
   能力和显式清单或默认组合。保持旧版本所需插件可执行，便于回滚。
2. 管理写操作开关开启后创建新版本或灰度，选择 schema、parser、chunker
   和向量配置；灰度保留全局版本向量模型与维度。
3. 开启目标版本写入，使新增事件进入目标写入会话。
4. 执行存量迁移，从原始发布修订、附件原文件和导入 PDF 来源重建历史内容；
   写入未开启时迁移会被拒绝。
5. 等待补齐完成，校验物理映射、必需字段、构建配置和当前流水线可执行性。
6. 通过校验后切换灰度或全局读选择。已有索引不会因默认配置变化自动升级。

测试应覆盖 id 非法/重复/未知、不可用诊断、注册目录、能力门禁、默认组合与
显式清单、首次部署偏好及失败关闭；分块器还应覆盖父子块结果与版本执行路由。
字段格式变化需验证映射摘要稳定性与投影；缓存隔离需覆盖 parser/chunker 身份。
回滚先选回已校验且当前部署可执行的旧版本，再关闭其不需要的功能开关。
多模态配置、派生资源和清理限制见 [多模态运维手册](multimodal-indexing.md)。
