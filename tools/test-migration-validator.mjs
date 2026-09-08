#!/usr/bin/env node
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';

const validator = path.resolve('tools/validate-migrations.mjs');
const temp = fs.mkdtempSync(path.join(os.tmpdir(), 'kwiki-migrations-'));
try {
  fs.writeFileSync(path.join(temp, 'V1__identity.sql'), '-- valid\n');
  fs.writeFileSync(path.join(temp, 'V2__wiki.sql'), '-- valid\n');
  let result = spawnSync(process.execPath, [validator, temp], { encoding: 'utf8' });
  if (result.status !== 0) throw new Error(`valid fixture failed: ${result.stderr}`);

  fs.writeFileSync(path.join(temp, 'V2__duplicate.sql'), '-- invalid\n');
  result = spawnSync(process.execPath, [validator, temp], { encoding: 'utf8' });
  if (result.status === 0 || !result.stderr.includes('duplicate version')) {
    throw new Error('duplicate fixture was accepted');
  }
  console.log('migration validator fixtures passed');
} finally {
  fs.rmSync(temp, { recursive: true, force: true });
}
