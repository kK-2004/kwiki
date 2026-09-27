import { describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen } from "@testing-library/vue";
import { createPinia } from "pinia";
import { createMemoryHistory, createRouter } from "vue-router";
import AdminLayout from "../src/components/AdminLayout.vue";

const logout = vi.fn();
vi.mock("../src/auth", () => ({ useAuth: () => ({ user: { username: "root", admin: true }, logout }) }));

async function mount() {
  vi.stubGlobal("matchMedia", vi.fn(() => ({ matches: false, addEventListener() {}, removeEventListener() {} })));
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: "/", component: { template: "<div/>" } }, { path: "/knowledge-graphs", component: { template: "<div/>" } }, { path: "/login", component: { template: "<div/>" } }] });
  await router.push("/knowledge-graphs");
  render(AdminLayout, { slots: { default: "<p>页面内容</p>" }, global: { plugins: [createPinia(), router] } });
  return router;
}

describe("AdminLayout", () => {
  it("渲染两个导航入口并高亮当前页", async () => {
    await mount();
    expect(screen.getByRole("link", { name: /索引管理/ })).toBeTruthy();
    expect(screen.getByRole("link", { name: /知识图谱/ }).getAttribute("aria-current")).toBe("page");
    expect(screen.getByText("页面内容")).toBeTruthy();
  });

  it("提供主题分段控件与退出", async () => {
    const router = await mount();
    expect(screen.getAllByRole("radio")).toHaveLength(3);
    await fireEvent.click(screen.getByRole("button", { name: "退出" }));
    expect(logout).toHaveBeenCalled();
    await vi.waitFor(() => expect(router.currentRoute.value.path).toBe("/login"));
  });
});
