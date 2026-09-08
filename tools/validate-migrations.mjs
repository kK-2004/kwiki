#!/usr/bin/env node
import fs from 'node:fs';
import path from 'node:path';

const rootArg = process.argv[2] ?? 'src/main/resources/db/migration';
const root = path.resolve(rootArg);
const pattern = /^V(\d+)__([A-Za-z0-9][A-Za-z0-9_-]*)\.sql$/;

if (!fs.existsSync(root) || !fs.statSync(root).isDirectory()) {
  console.error(`migration directory does not exist: ${root}`);
  process.exit(1);
}

const names = fs.readdirSync(root).filter((name) => name.endsWith('.sql')).sort();
const versions = new Map();
const errors = [];
for (const name of names) {
  const match = pattern.exec(name);
  if (!match) {
    errors.push(`${name}: expected V<N>__description.sql`);
    continue;
  }
  const version = Number(match[1]);
  if (versions.has(version)) errors.push(`${name}: duplicate version V${version} (already ${versions.get(version)})`);
  versions.set(version, name);
  if (fs.statSync(path.join(root, name)).size === 0) errors.push(`${name}: migration is empty`);
}

const ordered = [...versions.keys()].sort((a, b) => a - b);
if (ordered.some((version, index) => index > 0 && version <= ordered[index - 1])) {
  errors.push('migration versions are not strictly increasing');
}
if (errors.length) {
  console.error(errors.map((error) => `- ${error}`).join('\n'));
  process.exit(1);
}
console.log(`validated ${ordered.length} migrations in ${root}`);
