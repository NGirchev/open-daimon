#!/usr/bin/env node
/**
 * Stop Hook: Cost Tracker
 *
 * Appends session usage metrics to ~/.claude/metrics/costs.jsonl.
 * Standalone — no external dependencies.
 */

'use strict';

const fs = require('fs');
const path = require('path');
const os = require('os');

const MAX_STDIN = 1024 * 1024;
const CLAUDE_DIR = path.join(os.homedir(), '.claude');
const METRICS_DIR = path.join(CLAUDE_DIR, 'metrics');
const COSTS_FILE = path.join(METRICS_DIR, 'costs.jsonl');

function ensureDir(dirPath) {
  if (!fs.existsSync(dirPath)) {
    fs.mkdirSync(dirPath, { recursive: true });
  }
}

function toNumber(value) {
  const n = Number(value);
  return Number.isFinite(n) ? n : 0;
}

function estimateCost(model, inputTokens, outputTokens) {
  const rates = {
    haiku:  { in: 0.8,  out: 4.0  },
    sonnet: { in: 3.0,  out: 15.0 },
    opus:   { in: 15.0, out: 75.0 },
  };

  const normalized = String(model || '').toLowerCase();
  let rate = rates.sonnet;
  if (normalized.includes('haiku')) rate = rates.haiku;
  if (normalized.includes('opus')) rate = rates.opus;

  return Math.round(
    ((inputTokens / 1_000_000) * rate.in + (outputTokens / 1_000_000) * rate.out) * 1e6
  ) / 1e6;
}

let raw = '';
process.stdin.setEncoding('utf8');
process.stdin.on('data', chunk => {
  if (raw.length < MAX_STDIN) raw += chunk.substring(0, MAX_STDIN - raw.length);
});

process.stdin.on('end', () => {
  try {
    const input = raw.trim() ? JSON.parse(raw) : {};
    const usage = input.usage || input.token_usage || {};
    const inputTokens = toNumber(usage.input_tokens || usage.prompt_tokens || 0);
    const outputTokens = toNumber(usage.output_tokens || usage.completion_tokens || 0);
    const model = String(input.model || process.env.CLAUDE_MODEL || 'unknown');
    const sessionId = String(process.env.CLAUDE_SESSION_ID || 'default');

    ensureDir(METRICS_DIR);

    const row = {
      timestamp: new Date().toISOString(),
      session_id: sessionId,
      project: 'open-daimon',
      model,
      input_tokens: inputTokens,
      output_tokens: outputTokens,
      estimated_cost_usd: estimateCost(model, inputTokens, outputTokens),
    };

    fs.appendFileSync(COSTS_FILE, JSON.stringify(row) + '\n', 'utf8');
  } catch {
    // Non-blocking — never fail the session.
  }

  process.stdout.write(raw);
});
