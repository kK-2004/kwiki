# 管理端模板选型与固定（任务 1.2）

## 选定版本

- 模板：[pure-admin-thin](https://github.com/pure-admin/pure-admin-thin)（vue-pure-admin 官方精简模板）
- 固定 release：**v6.2.0**
- 固定 commit：`f0ff132561ab684bb78379239adf29f9a38ac7f1`（tag v6.2.0，2025-10-30）
- 许可证：**MIT**（保留上游 `LICENSE` 及版权声明；`package.json` author: xiaoxian521）

## 兼容性核对

| 项 | 仓库基线（frontend/） | pure-admin-thin v6.2.0 | 结论 |
| --- | --- | --- | --- |
| Node | 本机 v25.6.1（仓库未设 engines/.nvmrc） | `^20.19.0 || >=22.13.0` | 兼容 |
| pnpm | 10.30.3（frontend 已用 pnpm-lock.yaml） | `>=9` | 兼容 |
| Vue | 3.5 | ^3.5.22 | 兼容 |
| Vite | 6（frontend） | 7（管理端独立工程） | 独立工具链，互不影响 |
| TypeScript / vue-tsc | 5.7 / 2.x | 5.9 / 3.x | 管理端独立 tsconfig |
| UI 库 | Naive UI（业务前端） | Element Plus | 管理端独立选型，符合设计 |

说明：`admin-frontend/` 是独立构建入口，允许与业务前端使用不同 Vite/Pinia 大版本；
两者仅共享后端 JWT 认证与 API。Node 需 `>=22.13`（Vite 7 要求），CI/本地均满足。

## 固定策略

1. 任务 11.1 落地时以上述 commit 为准生成 `admin-frontend/`（取 tag 源码包，
   不浮动跟踪 main）。
2. `pnpm-lock.yaml` 一并入库，`package.json` 中模板依赖保持 v6.2.0 发布时的版本区间，
   升级只能整体评估后手动进行。
3. 保留上游 `LICENSE` 文件与第三方许可文件（`LICENSE`、依赖许可清单随构建输出）。
4. 上游安全修复同步策略：关注 pure-admin-thin releases；仅安全修复时按补丁
   cherry-pick 并在本文档追加记录，不整体滚动升级。
