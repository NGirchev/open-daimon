#!/usr/bin/env node
/**
 * Stop Hook: Cost Tracker
 *
 * Aggregates usage from transcript_path (JSONL written by Claude Code)
 * across all assistant turns in the session, estimates cost and appends
 * a row to ~/.claude/metrics/costs.jsonl.
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

// USD per 1M tokens. Cache write (5m ephemeral) = input × 1.25, cache read = input × 0.1.
const RATES = {
  haiku:  { in: 1.0,  out: 5.0,  cacheWrite: 1.25,  cacheRead: 0.1 },
  sonnet: { in: 3.0,  out: 15.0, cacheWrite: 3.75,  cacheRead: 0.3 },
  opus:   { in: 15.0, out: 75.0, cacheWrite: 18.75, cacheRead: 1.5 },
};

function ensureDir(dirPath) {
  if (!fs.existsSync(dirPath)) fs.mkdirSync(dirPath, { recursive: true });
}

function pickRate(model) {
  const m = String(model || '').toLowerCase();
  if (m.includes('haiku')) return RATES.haiku;
  if (m.includes('opus')) return RATES.opus;
  return RATES.sonnet;
}

function num(v) {
  const n = Number(v);
  return Number.isFinite(n) ? n : 0;
}

function estimateCost(model, usage) {
  const rate = pickRate(model);
  const cost =
    (usage.input_tokens / 1_000_000) * rate.in +
    (usage.output_tokens / 1_000_000) * rate.out +
    (usage.cache_creation_input_tokens / 1_000_000) * rate.cacheWrite +
    (usage.cache_read_input_tokens / 1_000_000) * rate.cacheRead;
  return Math.round(cost * 1e6) / 1e6;
}

function aggregateTranscript(transcriptPath) {
  const acc = {
    model: 'unknown',
    usage: {
      input_tokens: 0,
      output_tokens: 0,
      cache_creation_input_tokens: 0,
      cache_read_input_tokens: 0,
    },
  };
  let content;
  try {
    content = fs.readFileSync(transcriptPath, 'utf8');
  } catch {
    return acc;
  }
  for (const line of content.split('\n')) {
    const trimmed = line.trim();
    if (!trimmed) continue;
    let entry;
    try { entry = JSON.parse(trimmed); } catch { continue; }
    if (entry.type !== 'assistant' || !entry.message) continue;
    const u = entry.message.usage || {};
    acc.usage.input_tokens += num(u.input_tokens);
    acc.usage.output_tokens += num(u.output_tokens);
    acc.usage.cache_creation_input_tokens += num(u.cache_creation_input_tokens);
    acc.usage.cache_read_input_tokens += num(u.cache_read_input_tokens);
    if (entry.message.model) acc.model = entry.message.model;
  }
  return acc;
}

let raw = '';
process.stdin.setEncoding('utf8');
process.stdin.on('data', chunk => {
  if (raw.length < MAX_STDIN) raw += chunk.substring(0, MAX_STDIN - raw.length);
});

process.stdin.on('end', () => {
  try {
    const input = raw.trim() ? JSON.parse(raw) : {};
    const transcriptPath = input.transcript_path || '';
    const sessionId = input.session_id || process.env.CLAUDE_SESSION_ID || 'unknown';

    const agg = transcriptPath && fs.existsSync(transcriptPath)
      ? aggregateTranscript(transcriptPath)
      : { model: 'unknown', usage: { input_tokens: 0, output_tokens: 0, cache_creation_input_tokens: 0, cache_read_input_tokens: 0 } };

    ensureDir(METRICS_DIR);

    const row = {
      timestamp: new Date().toISOString(),
      session_id: sessionId,
      project: 'open-daimon',
      model: agg.model,
      input_tokens: agg.usage.input_tokens,
      output_tokens: agg.usage.output_tokens,
      cache_creation_input_tokens: agg.usage.cache_creation_input_tokens,
      cache_read_input_tokens: agg.usage.cache_read_input_tokens,
      estimated_cost_usd: estimateCost(agg.model, agg.usage),
    };

    fs.appendFileSync(COSTS_FILE, JSON.stringify(row) + '\n', 'utf8');
  } catch {
    // Non-blocking — never fail the session.
  }
  process.exit(0);
});
