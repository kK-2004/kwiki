# kwiki 工程手册

一段普通文本，包含 **加粗**、*斜体* 和 `行内代码`。

## 部署流程

1. 准备外部 MySQL、Redis、内容中心与 Elasticsearch
2. 注入 `KWIKI_*` 环境变量
3. 启动应用并检查 readiness

### 检查清单

- [ ] 数据库迁移执行
- [ ] readiness 全绿
- [ ] 无 Docker 依赖

## 兼容性矩阵

| 中间件 | 最低版本 | 说明 |
| --- | --- | --- |
| MySQL | 8.0 | 需要 CHECK 约束 |
| Elasticsearch | 8.x | dense_vector |

> 引用：kwiki 不负责创建中间件容器。
