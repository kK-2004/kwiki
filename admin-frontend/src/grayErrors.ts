/** 幂等重放返回的稳定英文错误码 → 中文提示；其余错误（后端已是中文）原样展示。 */
const MESSAGES: Record<string, string> = {
  management_command_in_progress: "上一次相同操作仍在进行，请稍后刷新",
  previous_management_command_failed: "上一次相同操作失败，请刷新后重试",
  invalid_request: "请求无效，请刷新后重试",
};

export function grayErrorMessage(error: unknown, fallback: string): string {
  if (!(error instanceof Error) || !error.message) return fallback;
  return MESSAGES[error.message] ?? error.message;
}
