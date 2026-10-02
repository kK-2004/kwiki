/** 管理端展示用的中文映射：服务端保持英文枚举，界面统一在此翻译，未知值原样显示。 */

const AUDIT_ACTIONS: Record<string, string> = {
  CREATE: "创建版本",
  EDIT: "编辑配置",
  WRITE_ENABLE: "开启写入",
  WRITE_DISABLE: "关闭写入",
  MIGRATE: "开始存量迁移",
  VALIDATE: "校验",
  SELECT: "切换线上版本",
  RECONCILE: "别名自动对账",
  DELETE_PHYSICAL_INDEX: "清理物理索引",
  RUN_PAUSE: "暂停迁移",
  RUN_RESUME: "恢复迁移",
  RUN_CANCEL: "取消迁移",
  GRAY_CREATE: "创建灰度",
  GRAY_SYNC: "灰度开始同步",
  GRAY_SWITCH: "灰度切换",
  GRAY_SWITCH_BACK: "灰度切回",
  GRAY_END: "结束灰度",
  // 旧流程留下的历史记录
  REBUILD: "重建（旧）",
  PREPARE: "开始补齐（旧）",
  DISABLE: "停用写入（旧）",
  REENABLE: "重新启用（旧）",
};

const OUTCOMES: Record<string, string> = {
  PENDING: "执行中",
  SUCCESS: "成功",
  FAILURE: "失败",
  RECOVERED: "已对账恢复",
};

const RESULTS: Record<string, string> = {
  SUCCESS: "成功",
  PASS: "校验通过",
  FAIL: "校验未通过",
  REQUEST_ACCEPTED: "—",
};

const RUN_KINDS: Record<string, string> = {
  MIGRATION: "存量迁移",
  MANUAL: "重建（旧）",
  INITIAL: "首次构建（旧）",
};

const RUN_STATES: Record<string, string> = {
  PENDING: "排队中",
  RUNNING: "进行中",
  PAUSED: "已暂停",
  COMPLETED: "已完成",
  FAILED: "失败",
  CANCELLED: "已取消",
};

const pick = (table: Record<string, string>, value?: string | null) => (value ? table[value] ?? value : "—");

export const auditActionLabel = (value?: string | null) => pick(AUDIT_ACTIONS, value);
export const auditOutcomeLabel = (value?: string | null) => pick(OUTCOMES, value);
export const auditResultLabel = (value?: string | null) => pick(RESULTS, value);
export const runKindLabel = (value?: string | null) => pick(RUN_KINDS, value);
export const runStateLabel = (value?: string | null) => pick(RUN_STATES, value);

/** 校验摘要中的检查项：布尔项为 false、计数项大于 0 即为未通过。 */
const CHECKS: Record<string, string> = {
  revision: "配置修订不一致",
  manifest: "构建清单不一致",
  mapping: "索引映射异常",
  missingResources: "缺失资源",
  staleDocuments: "过期文档",
  orphanChildren: "孤立子块",
  malformedVectors: "异常向量",
  mixedManifest: "混合解析版本文档",
  synchronized: "双写未同步",
  smoke: "冒烟查询失败",
};

export function validationText(summary?: string | null): { passed: boolean | null; text: string } {
  if (!summary) return { passed: null, text: "尚未校验" };
  if (summary === "validation passed") return { passed: true, text: "校验通过" };
  const body = summary.replace(/^structure validation failed:\s*/, "");
  const problems: string[] = [];
  for (const pair of body.split(/,\s*/)) {
    const [key, raw] = pair.split("=");
    const label = CHECKS[key?.trim()];
    if (!label || raw == null) continue;
    const value = raw.trim();
    if (value === "false") problems.push(label);
    else if (/^\d+$/.test(value) && Number(value) > 0) problems.push(`${label} ${value}`);
    else if (key.trim() === "mapping" && value !== "valid") problems.push(label);
  }
  return { passed: false, text: problems.length ? `未通过：${problems.join("、")}` : "未通过" };
}
