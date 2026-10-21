#!/usr/bin/env node
// 由 dragonball-sample.json 生成 kwiki-rag.jsonl 的关键词组：从标准答案抽取必需信息单元
// （日期、数字+单位、中文数词、量刑短语），短语挖掘兜底；别名做千分位/万吨/补零扩展。
// 与 sample 同目录的 keyword-overrides.json 按 id 整体覆盖自动抽取结果（人工审阅后的关键词）。
// 生成后逐题执行与 EvalDatasetFileTest 相同的校验，任何一条不满足即失败。
// 用法：node tools/eval/build-kwiki-rag.mjs <dragonball-sample.json> <语料目录> <输出 jsonl>
import { readFileSync, writeFileSync, existsSync } from 'node:fs';
import { dirname, join } from 'node:path';

const [samplePath, corpusDir, outputPath] = process.argv.slice(2);
if (!samplePath || !corpusDir || !outputPath) {
  console.error('usage: build-kwiki-rag.mjs <dragonball-sample.json> <corpusDir> <out.jsonl>');
  process.exit(2);
}

// 与 KeywordMatcher.normalize 保持一致：NFKC、小写、去全部空白、去千分位逗号
function normalize(text) {
  if (!text) return '';
  return text.normalize('NFKC').toLowerCase().replace(/\s+/g, '')
    .replace(/(?<=\d),(?=\d{3})/g, '');
}

// 语料文档：docId → 归一化正文
const documents = new Map();
const manifest = JSON.parse(readFileSync(join(corpusDir, 'manifest.json'), 'utf8'));
for (const entry of manifest) {
  documents.set(entry.docId, normalize(readFileSync(join(corpusDir, entry.file), 'utf8')));
}

const UNITS = ['万元', '亿元', '万美元', '亿美元', '吨', '千克', '公斤', '克', '公里', '千米', '米',
  '平方米', '万平方米', '%', '°C', '°c', '℃', '度', '岁', '人', '次', '名', '位', '起', '项', '家',
  '笔', '份', '台', '辆', '只', '头', '倍', '小时', '天', '周', '月', '日', '年', '股', '款', '宗', '元'];

// 数字（含千分位与小数）+ 单位；按单位长度优先匹配
const NUMBER_WITH_UNIT = new RegExp(`(\\d{1,3}(?:,\\d{3})+|\\d+(?:\\.\\d+)?)(?:${[...new Set(UNITS)].sort((a, b) => b.length - a.length).join('|')})`, 'g');
const FULL_DATE = /(\d{4}年\d{1,2}月\d{1,2}日)/g;
const YEAR_MONTH = /(\d{4}年\d{1,2}月)(?!\d{1,2}日)/g;
const MONTH_DAY = /(\d{1,2}月\d{1,2}日)/g;
const CN_COUNT = /([一二两三四五六七八九十百]+(?:次|名|位|起|项|家|笔|份|人|天|年|月|小时|例|份))/g;
const SENTENCE = /(有期徒刑[一二三四五七八九十百\d]+年|无期徒刑|死刑)/g;

function numberVariants(text) {
  const variants = new Set([text]);
  const m = text.match(/^(\d{1,3}(?:,\d{3})+|\d+(?:\.\d+)?)((.*))$/);
  if (m) {
    const [_, digits, rest] = m;
    const plain = digits.replace(/,/g, '');
    variants.add(plain + rest);
    if (plain.replace(/\D/g, '').length >= 5) {
      variants.add(plain.replace(/\B(?=(\d{3})+(?!\d))/g, ',') + rest);
    }
    const value = Number(plain);
    if (rest === '吨' && Number.isInteger(value) && value % 10000 === 0 && value > 0) {
      variants.add(`${value / 10000}万吨`);
    }
    if (rest === '元' && Number.isInteger(value) && value % 10000 === 0 && value > 0) {
      variants.add(`${value / 10000}万元`);
    }
  }
  return [...variants].filter((v) => v && normalize(v));
}

function dateVariants(text) {
  const variants = new Set([text]);
  const m = text.match(/^(?:(\d{4})年)?(\d{1,2})月(?:(\d{1,2})日)?$/);
  if (m) {
    const [, year, month, day] = m;
    const padded = `${year ? String(year) + '年' : ''}${String(month).padStart(2, '0')}月${day ? String(day).padStart(2, '0') + '日' : ''}`;
    variants.add(padded);
  }
  return [...variants].filter((v) => v && normalize(v));
}

/** 从答案中挖掘出现在指定文档里的最长短语（2~8 字），排除纯数字与题干词。 */
function minePhrases(answer, doc, queryNorm) {
  const raw = answer.normalize('NFKC')
    .replace(/[\s，。；：、！？""''（）()\[\]【】,\.;:!?*#>|`]/g, '');
  const trim = (piece) => piece.replace(/^[的了和与及或在是被对于将把其该各等并号年月日时]+/, '')
    .replace(/[的了和与及或在是对于将把其该各等并加]+$/, '');
  const text = raw;
  const found = [];
  let index = 0;
  while (index < text.length) {
    let best = '';
    for (let len = 12; len >= 2; len--) {
      const piece = text.slice(index, index + len);
      if (piece.length < len) continue;
      if (/\d/.test(piece) && len <= 3) continue;
      if (doc.includes(normalize(piece)) && !queryNorm.includes(normalize(piece))) {
        best = piece;
        break;
      }
    }
    if (best) {
      const trimmed = trim(best).length >= 2 ? trim(best) : best;
      if (trimmed.length >= 2 && !queryNorm.includes(normalize(trimmed))) found.push(trimmed);
      index += best.length;
    } else {
      index += 1;
    }
  }
  // 只保留互不为子串的最长短语
  const maximal = found.filter((piece) => !found.some((other) => other !== piece && other.includes(piece)));
  return [...new Set(maximal)].sort((a, b) => b.length - a.length);
}

const sample = JSON.parse(readFileSync(samplePath, 'utf8'));
const lines = [];
const problems = [];

// 人工覆盖：{ "<id>": { "keywords": [[别名...], ...], "reason": "..." } }，以下划线开头的键为说明
const overridesPath = join(dirname(samplePath), 'keyword-overrides.json');
const overrides = existsSync(overridesPath)
  ? Object.fromEntries(Object.entries(JSON.parse(readFileSync(overridesPath, 'utf8'))).filter(([id]) => !id.startsWith('_')))
  : {};
const caseIds = new Set(sample.cases.map((sample_case) => sample_case.id));
for (const [id, override] of Object.entries(overrides)) {
  if (!caseIds.has(id)) problems.push(`override ${id}: 测试集中没有这道题`);
  if (!Array.isArray(override.keywords)) problems.push(`override ${id}: 缺少 keywords`);
  if (!override.reason) problems.push(`override ${id}: 缺少 reason`);
}
for (const sample_case of sample.cases) {
  if (sample_case.negative) {
    lines.push(JSON.stringify({
      id: sample_case.id, source: 'dragonball', tier: sample_case.tier, category: sample_case.category,
      domain: sample_case.domain, query: sample_case.query, keywords: [], negative: true,
      sourceDocIds: sample_case.sourceDocIds, keypoints: sample_case.keypoints,
    }));
    if (overrides[sample_case.id]) problems.push(`override ${sample_case.id}: 负样本不应有关键词`);
    continue;
  }
  if (overrides[sample_case.id]) {
    lines.push(JSON.stringify({
      id: sample_case.id, source: 'dragonball', tier: sample_case.tier, category: sample_case.category,
      domain: sample_case.domain, query: sample_case.query, keywords: overrides[sample_case.id].keywords,
      negative: false, sourceDocIds: sample_case.sourceDocIds, keypoints: sample_case.keypoints,
    }));
    continue;
  }
  const queryNorm = normalize(sample_case.query);
  const corpusNorm = sample_case.sourceDocIds.map((id) => documents.get(id) || '').join('\u0000');
  const answerNfkc = sample_case.answer.normalize('NFKC');
  const groups = [];
  const seen = new Set();
  const push = (aliases, kind) => {
    const unique = [...new Set(aliases.filter(Boolean))];
    const usable = unique.filter((alias) => !queryNorm.includes(normalize(alias)));
    if (usable.length === 0) return false;
    const key = usable.map(normalize).sort().join('|');
    if (seen.has(key)) return false;
    const inCorpus = usable.some((alias) => corpusNorm.includes(normalize(alias)));
    if (!inCorpus) return false;
    seen.add(key);
    groups.push({ aliases: unique, kind });
    return true;
  };

  for (const match of answerNfkc.matchAll(FULL_DATE)) push(dateVariants(match[1]), 'date');
  const coveredByExisting = (text) => groups.some((group) => group.aliases.some(
    (alias) => normalize(alias).includes(normalize(text))));
  for (const match of answerNfkc.matchAll(YEAR_MONTH)) {
    if (!coveredByExisting(match[1])) push(dateVariants(match[1]), 'year-month');
  }
  for (const match of answerNfkc.matchAll(MONTH_DAY)) {
    if (!coveredByExisting(match[1])) push(dateVariants(match[1]), 'month-day');
  }
  for (const match of answerNfkc.matchAll(SENTENCE)) push([match[1]], 'sentence');
  for (const match of answerNfkc.matchAll(NUMBER_WITH_UNIT)) {
    // 年/月/日碎片已被日期组覆盖时不再单列
    if (/[年月日]$/.test(match[0]) && groups.some((group) => group.aliases.some(
      (alias) => normalize(alias).includes(normalize(match[0]))))) continue;
    push(numberVariants(match[0]), 'number');
  }
  for (const match of answerNfkc.matchAll(CN_COUNT)) push([match[1]], 'cn-count');

  // references 是来源文档的摘录，答案过于简略（如「某公司更早」）时从中补日期与数字
  const referenceText = sample_case.references.join('\n').normalize('NFKC');
  const referenceCandidates = [];
  for (const match of referenceText.matchAll(FULL_DATE)) referenceCandidates.push(dateVariants(match[1]));
  for (const match of referenceText.matchAll(YEAR_MONTH)) {
    if (!referenceCandidates.some((aliases) => aliases.some((alias) => normalize(alias).includes(normalize(match[1]))))) {
      referenceCandidates.push(dateVariants(match[1]));
    }
  }
  for (const match of referenceText.matchAll(MONTH_DAY)) {
    if (!referenceCandidates.some((aliases) => aliases.some((alias) => normalize(alias).includes(normalize(match[1]))))) {
      referenceCandidates.push(dateVariants(match[1]));
    }
  }
  for (const match of referenceText.matchAll(NUMBER_WITH_UNIT)) {
    if (/[年月日]$/.test(match[0]) && referenceCandidates.some((aliases) => aliases.some(
      (alias) => normalize(alias).includes(normalize(match[0]))))) continue;
    referenceCandidates.push(numberVariants(match[0]));
  }

  // 多文档覆盖：每个来源文档至少一组
  const covered = (docId) => {
    const doc = documents.get(docId) || '';
    return groups.some((group) => group.aliases.some((alias) => doc.includes(normalize(alias))));
  };
  for (const docId of sample_case.sourceDocIds) {
    if (covered(docId)) continue;
    const doc = documents.get(docId) || '';
    const fromReferences = referenceCandidates.find((aliases) =>
      aliases.some((alias) => doc.includes(normalize(alias)) && !queryNorm.includes(normalize(alias))));
    if ((fromReferences && push(fromReferences, 'ref-coverage')) === true) continue;
    const phrase = minePhrases(answerNfkc, doc, queryNorm).find((piece) => piece.length >= 2);
    if (phrase && push([phrase], 'coverage')) continue;
    const refPhrase = minePhrases(referenceText, doc, queryNorm).find((piece) => piece.length >= 2);
    if (refPhrase && push([refPhrase], 'ref-coverage')) continue;
    if (!phrase || !push([phrase], 'coverage')) {
      problems.push(`${sample_case.id}: doc ${docId} 无法从答案提取覆盖关键词`);
    }
  }

  // 数量下限：单一事实题允许 1 组，其余至少 2 组，用 references 候选与短语补足
  const minGroups = sample_case.category === '事实性问题' ? 1 : 2;
  while (groups.length < minGroups) {
    const fromReferences = referenceCandidates.find((aliases) => aliases.every(
      (alias) => !groups.some((group) => group.aliases.some((existing) => normalize(existing) === normalize(alias)))));
    if (fromReferences && push(fromReferences, 'ref-phrase')) continue;
    const phrase = minePhrases(answerNfkc, corpusNorm, queryNorm)
      .find((piece) => !groups.some((group) => group.aliases.some((alias) => normalize(alias) === normalize(piece)
        || normalize(piece).includes(normalize(alias)) || normalize(alias).includes(normalize(piece)))));
    if (!phrase || !push([phrase], 'phrase')) {
      problems.push(`${sample_case.id}: 关键词组不足（${groups.length}）`);
      break;
    }
  }
  if (groups.length > 6) groups.length = 6;

  lines.push(JSON.stringify({
    id: sample_case.id, source: 'dragonball', tier: sample_case.tier, category: sample_case.category,
    domain: sample_case.domain, query: sample_case.query, keywords: groups.map((group) => group.aliases),
    negative: false, sourceDocIds: sample_case.sourceDocIds, keypoints: sample_case.keypoints,
  }));
}

// —— 与 EvalDatasetFileTest 相同的门禁 ——
const dataset = lines.map((line) => JSON.parse(line));
const ids = new Set();
for (const row of dataset) {
  if (ids.has(row.id)) problems.push(`duplicate id ${row.id}`);
  ids.add(row.id);
  const queryNorm = normalize(row.query);
  const corpusNorm = row.sourceDocIds.map((id) => documents.get(id) || '').join('\u0000');
  if (!row.negative && row.keywords.length === 0) problems.push(`${row.id}: 非负样本没有关键词组`);
  if (row.keywords.length > 6) problems.push(`${row.id}: 关键词组超过 6 个`);
  for (const group of row.keywords) {
    if (group.length === 0) problems.push(`${row.id}: 空关键词组`);
    for (const alias of group) {
      if (queryNorm.includes(normalize(alias))) problems.push(`${row.id}: 别名「${alias}」出现在题干`);
    }
    if (!group.some((alias) => corpusNorm.includes(normalize(alias)))) {
      problems.push(`${row.id}: 关键词组 ${JSON.stringify(group)} 不在来源文档`);
    }
  }
  for (const docId of row.sourceDocIds) {
    if (row.sourceDocIds.length < 2 || row.negative) break;
    const doc = documents.get(docId) || '';
    if (!row.keywords.some((group) => group.some((alias) => doc.includes(normalize(alias))))) {
      problems.push(`${row.id}: 来源文档 ${docId} 无覆盖关键词`);
    }
  }
}

if (problems.length > 0) {
  console.error(`FAILED ${problems.length} problems:\n` + problems.slice(0, 30).join('\n'));
  process.exit(1);
}
writeFileSync(outputPath, lines.map((line) => line.replace(/","/g, '", "')).join('\n') + '\n', 'utf8');
console.log(`wrote ${lines.length} cases to ${outputPath}`);
