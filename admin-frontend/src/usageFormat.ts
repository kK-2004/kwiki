/** token 数量单位自适应：<1000 原样；之后 k / M / B，保留 3 位有效数字。 */
const UNITS: Array<[number, string]> = [[1e9, "B"], [1e6, "M"], [1e3, "k"]];

export function formatTokens(n: number): string {
  if (!Number.isFinite(n)) return "0";
  const abs = Math.abs(n);
  if (abs < 1000) return String(Math.round(n));
  for (let i = 0; i < UNITS.length; i++) {
    const [base, unit] = UNITS[i];
    if (abs >= base) {
      const scaled = Number((n / base).toPrecision(3));
      // 四舍五入后达到 1000 时进位到更大单位（如 999,999 → 1.00M）
      if (Math.abs(scaled) >= 1000 && i > 0) return `${(n / UNITS[i - 1][0]).toPrecision(3)}${UNITS[i - 1][1]}`;
      return `${(n / base).toPrecision(3)}${unit}`;
    }
  }
  return String(n);
}
