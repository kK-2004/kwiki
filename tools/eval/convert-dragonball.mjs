#!/usr/bin/env node
// 把 DragonBall 中文文档转换为评测语料（每篇一个 Markdown + manifest.json）。
// 用法：node tools/eval/convert-dragonball.mjs <dragonball_docs.jsonl> <输出目录>
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';

const [input, outDir] = process.argv.slice(2);
if (!input || !outDir) {
  console.error('usage: convert-dragonball.mjs <dragonball_docs.jsonl> <outDir>');
  process.exit(2);
}
const SUBJECT_FIELD = { Finance: 'company_name', Law: 'court_name', Medical: 'hospital_patient_name' };

mkdirSync(outDir, { recursive: true });
const manifest = [];
for (const line of readFileSync(input, 'utf8').split('\n')) {
  if (!line.trim()) continue;
  const doc = JSON.parse(line);
  if (doc.language !== 'zh') continue;
  const field = SUBJECT_FIELD[doc.domain];
  if (!field || !doc[field]) throw new Error(`doc ${doc.doc_id} has no subject field for ${doc.domain}`);
  const title = `${doc[field]}-${doc.doc_id}`;
  const file = `${doc.domain.toLowerCase()}-${doc.doc_id}.md`;
  const content = doc.content.endsWith('\n') ? doc.content : `${doc.content}\n`;
  writeFileSync(join(outDir, file), content, 'utf8');
  manifest.push({ file, title, domain: doc.domain, docId: doc.doc_id });
}
manifest.sort((a, b) => a.docId - b.docId);
const titles = new Set();
for (const entry of manifest) {
  if (titles.has(entry.title)) throw new Error(`duplicate title ${entry.title}`);
  titles.add(entry.title);
}
writeFileSync(join(outDir, 'manifest.json'), `${JSON.stringify(manifest, null, 2)}\n`, 'utf8');
console.log(`wrote ${manifest.length} documents to ${outDir}`);
