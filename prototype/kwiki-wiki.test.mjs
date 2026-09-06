import assert from 'node:assert/strict';
import { existsSync, readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import test from 'node:test';
import vm from 'node:vm';

const prototypeDir = dirname(fileURLToPath(import.meta.url));
const htmlPath = join(prototypeDir, 'kwiki-wiki.html');

function readPrototype() {
  assert.ok(existsSync(htmlPath), 'prototype/kwiki-wiki.html must exist');
  return readFileSync(htmlPath, 'utf8');
}

function loadPrototypeEngine() {
  const html = readPrototype();
  const match = html.match(/<script id="prototype-engine">([\s\S]*?)<\/script>/);
  assert.ok(match, 'prototype engine script must be embedded in the HTML');

  const context = {
    window: {},
    document: { addEventListener() {} },
    console,
  };
  vm.createContext(context);
  vm.runInContext(match[1], context);
  return context.window.KWikiPrototype;
}

test('renders the approved three-column workspace landmarks', () => {
  const html = readPrototype();
  assert.match(html, /<aside[^>]+aria-label="全局导航"/);
  assert.match(html, /<aside[^>]+aria-label="Wiki 页面导航"/);
  assert.match(html, /<main[^>]+id="page-content"/);
});

test('filters pages while preserving the matching knowledge path', () => {
  const engine = loadPrototypeEngine();
  const result = engine.filterPages('年假');
  assert.deepEqual(JSON.parse(JSON.stringify(result)), [
    { id: 'annual-leave', title: '年假', path: ['人力资源', '休假'] },
  ]);
});

test('selects a page and switches between knowledge and summary modes', () => {
  const engine = loadPrototypeEngine();
  assert.equal(engine.selectPage('leave-process').title, '请假流程');
  assert.equal(engine.setMode('summary'), 'summary');
  assert.deepEqual(JSON.parse(JSON.stringify(engine.getState())), {
    selectedPageId: 'leave-process',
    mode: 'summary',
    editing: false,
    previewing: false,
    historyOpen: false,
    expandedNodes: ['human-resources', 'leave'],
  });
});

test('supports edit preview and revision-history demo interactions', () => {
  const engine = loadPrototypeEngine();
  engine.startEditing();
  const preview = engine.previewMarkdown('# 年假新规\n\n- 入职满一年：10天');
  assert.match(preview, /<h1>年假新规<\/h1>/);
  assert.match(preview, /<li>入职满一年：10天<\/li>/);
  assert.equal(engine.openHistory(), true);
  assert.equal(engine.getState().previewing, true);
});

test('includes responsive navigation controls with accessible names', () => {
  const html = readPrototype();
  assert.match(html, /aria-label="打开全局导航"/);
  assert.match(html, /aria-label="打开页面目录"/);
  assert.match(html, /@media\s*\(max-width:\s*1023px\)/);
});
