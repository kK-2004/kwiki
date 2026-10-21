import { describe, expect, it } from "vitest";
import { formatTokens } from "../src/usageFormat";

describe("formatTokens", () => {
  it("小于 1000 原样显示", () => {
    expect(formatTokens(0)).toBe("0");
    expect(formatTokens(999)).toBe("999");
  });
  it("按 k / M / B 自适应并保留 3 位有效数字", () => {
    expect(formatTokens(1000)).toBe("1.00k");
    expect(formatTokens(12_345)).toBe("12.3k");
    expect(formatTokens(999_499)).toBe("999k");
    expect(formatTokens(4_560_000)).toBe("4.56M");
    expect(formatTokens(1_200_000_000)).toBe("1.20B");
  });
  it("进位到下一单位", () => {
    expect(formatTokens(999_999)).toBe("1.00M");
  });
  it("非有限数显示为 0", () => {
    expect(formatTokens(Number.NaN)).toBe("0");
  });
});
