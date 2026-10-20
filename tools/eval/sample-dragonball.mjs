#!/usr/bin/env node
// DragonBall 中文 query 分层抽样（固定种子，可复现）：按题型配额抽 200 题，并按 50 ⊂ 100 ⊂ 200 分配 tier。
// 用法：node tools/eval/sample-dragonball.mjs <dragonball_queries.jsonl> <输出 json>
import { readFileSync, writeFileSync } from 'node:fs';

const [input, output] = process.argv.slice(2);
if (!input || !output) {
  console.error('usage: sample-dragonball.mjs <dragonball_queries.jsonl> <out.json>');
  process.exit(2);
}
const SEED = 20261020;
// 同名主体文档（doc 4 与 doc 24 均为「创新科技有限公司」），引用它们的题答案有歧义，排除
const EXCLUDED_DOC_IDS = new Set([4, 24]);
// 每个题型在 tier 50 / 100 / 200 的累计配额
const QUOTAS = {
  '无关无解问': [4, 8, 16],
  '事实性问题': [10, 21, 42],
  '多跳推理问题': [8, 15, 30],
  '总结性问题': [9, 17, 35],
  '多文档信息整合问题': [10, 21, 42],
  '多文档对比问题': [6, 11, 22],
  '多文档时间序列问题': [3, 7, 13],
};
const DOMAIN_SHARE = { Finance: 40, Law: 30, Medical: 38 };
const DOMAIN_CODE = { Finance: 'fin', Law: 'law', Medical: 'med' };

function mulberry32(seed) {
  let a = seed;
  return () => {
    a |= 0; a = (a + 0x6d2b79f5) | 0;
    let t = Math.imul(a ^ (a >>> 15), 1 | a);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}
const rand = mulberry32(SEED);
function shuffle(list) {
  const items = [...list];
  for (let i = items.length - 1; i > 0; i--) {
    const j = Math.floor(rand() * (i + 1));
    [items[i], items[j]] = [items[j], items[i]];
  }
  return items;
}

const pools = {};
for (const type of Object.keys(QUOTAS)) pools[type] = { Finance: [], Law: [], Medical: [] };
for (const line of readFileSync(input, 'utf8').split('\n')) {
  if (!line.trim()) continue;
  const row = JSON.parse(line);
  if (row.language !== 'zh') continue;
  const type = row.query.query_type;
  if (!(type in QUOTAS)) continue;
  const docIds = row.ground_truth.doc_ids;
  if (docIds.some((id) => EXCLUDED_DOC_IDS.has(id))) continue;
  const negative = type === '无关无解问';
  if (!negative && row.ground_truth.references.length === 0) continue;
  pools[type][row.domain].push(row);
}
for (const type of Object.keys(pools)) {
  for (const domain of Object.keys(DOMAIN_SHARE)) {
    pools[type][domain].sort((a, b) => a.query.query_id - b.query.query_id);
    pools[type][domain] = shuffle(pools[type][domain]);
  }
}

const cases = [];
for (const [type, [q50, q100, q200]] of Object.entries(QUOTAS)) {
  const picked = { Finance: 0, Law: 0, Medical: 0 };
  for (let index = 0; index < q200; index++) {
    // 每一步选「已选数 / 占比」最小且仍有余量的领域，保证任意前缀的领域分布都接近语料比例
    const domain = Object.keys(DOMAIN_SHARE)
      .filter((d) => pools[type][d].length > 0)
      .sort((a, b) => (picked[a] + 1) / DOMAIN_SHARE[a] - (picked[b] + 1) / DOMAIN_SHARE[b])[0];
    if (!domain) throw new Error(`not enough queries for ${type}`);
    const row = pools[type][domain].shift();
    picked[domain]++;
    const tier = index < q50 ? 50 : index < q100 ? 100 : 200;
    cases.push({
      id: `db-zh-${DOMAIN_CODE[domain]}-${String(row.query.query_id).padStart(4, '0')}`,
      tier,
      category: type,
      domain,
      query: row.query.content,
      negative: type === '无关无解问',
      sourceDocIds: row.ground_truth.doc_ids,
      answer: row.ground_truth.content,
      references: row.ground_truth.references,
      keypoints: row.ground_truth.keypoints,
    });
  }
}
const typeOrder = Object.keys(QUOTAS);
cases.sort((a, b) => a.tier - b.tier || typeOrder.indexOf(a.category) - typeOrder.indexOf(b.category)
  || a.id.localeCompare(b.id));
writeFileSync(output, `${JSON.stringify({ seed: SEED, quotas: QUOTAS, cases }, null, 2)}\n`, 'utf8');
console.log(`sampled ${cases.length} cases`);
