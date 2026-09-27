import { describe, expect, it } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/vue";
import ElementPlus from "element-plus";
import CurrentIndexCards from "../src/components/CurrentIndexCards.vue";
import type { Version } from "../src/api";

const version = (n: number, parser: string, selected: boolean, canSelect: boolean, kbScoped = false) => ({
  versionNumber: n, physicalName: `kwiki-chunks-v${n}`, selected, kbScoped,
  configuration: { parserVersion: parser, chunkerVersion: "c", embeddingProvider: "d", embeddingModel: "m", embeddingDimensions: 1024, mappingSchemaVersion: 3 },
  allowedActions: { select: canSelect },
}) as unknown as Version;

describe("全局索引顶部卡片", () => {
  it("一眼展示当前别名指向与解析器显示名", () => {
    render(CurrentIndexCards, { props: { versions: [version(1, "kwiki-parse-1", true, false)], aliasTargets: ["kwiki-chunks-v1"] }, global: { plugins: [ElementPlus] } });
    expect(screen.getByText("kwiki-chunks-v1")).toBeTruthy();
    expect(screen.getByText("tika-v1")).toBeTruthy();
  });

  it("点击卡片列出可发布的全局版本，排除灰度版本，并说明解析器随版本切换", async () => {
    render(CurrentIndexCards, {
      props: { versions: [version(1, "kwiki-parse-1", true, false), version(2, "kwiki-parse-2", false, true), version(3, "kwiki-parse-2", false, false, true)], aliasTargets: ["kwiki-chunks-v1"] },
      global: { plugins: [ElementPlus] },
    });
    await fireEvent.click(screen.getByRole("button", { name: /当前解析器/ }));
    await waitFor(() => expect(screen.getByText(/解析器随索引版本一起切换/)).toBeTruthy());
    expect(screen.getByRole("radio", { name: /kwiki-chunks-v2/ })).toBeTruthy();
    expect(screen.queryByRole("radio", { name: /kwiki-chunks-v3/ })).toBeNull();
  });

  it("即使服务端允许 select，灰度版本也不出现在候选中", async () => {
    render(CurrentIndexCards, {
      props: { versions: [version(1, "kwiki-parse-1", true, false), version(4, "kwiki-parse-2", false, true, true)], aliasTargets: ["kwiki-chunks-v1"] },
      global: { plugins: [ElementPlus] },
    });
    await fireEvent.click(screen.getByRole("button", { name: /当前别名/ }));
    await waitFor(() => expect(screen.getByText(/暂无可发布的版本/)).toBeTruthy());
    expect(screen.queryByRole("radio", { name: /kwiki-chunks-v4/ })).toBeNull();
  });

  it("选择版本后点击下一步只发出 select 事件，交由页面的输入确认流程完成切换", async () => {
    const v2 = version(2, "kwiki-parse-2", false, true);
    const { emitted } = render(CurrentIndexCards, {
      props: { versions: [version(1, "kwiki-parse-1", true, false), v2], aliasTargets: ["kwiki-chunks-v1"] },
      global: { plugins: [ElementPlus] },
    });
    await fireEvent.click(screen.getByRole("button", { name: /当前别名/ }));
    await waitFor(() => expect(screen.getByRole("radio", { name: /kwiki-chunks-v2/ })).toBeTruthy());
    await fireEvent.click(screen.getByRole("radio", { name: /kwiki-chunks-v2/ }));
    await fireEvent.click(screen.getByRole("button", { name: "下一步" }));
    expect(emitted().select).toEqual([[v2]]);
  });
});
