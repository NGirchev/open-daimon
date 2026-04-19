#!/usr/bin/env node
/**
 * Stop Hook: Continuous Learning — Session Evaluator
 *
 * Runs at session end. If the session was substantial (>= N user messages),
 * suggests running /learn or /learn-eval to extract reusable patterns.
 *
 * Standalone — no external dependencies.
 */

'use strict';

const fs = require('fs');
const path = require('path');
const os = require('os');

const MAX_STDIN = 1024 * 1024;
const MIN_SESSION_LENGTH = 10;
const LEARNED_SKILLS_DIR = path.join(os.homedir(), '.claude', 'skills', 'learned');

function countUserMessages(transcriptPath) {
  let content;
  try {
    content = fs.readFileSync(transcriptPath, 'utf8');
  } catch {
    return 0;
  }
  const matches = content.match(/"type"\s*:\s*"user"/g);
  return matches ? matches.length : 0;
}

function countLearnedSkills() {
  try {
    if (!fs.existsSync(LEARNED_SKILLS_DIR)) return 0;
    return fs.readdirSync(LEARNED_SKILLS_DIR).filter(f => f.endsWith('.md')).length;
  } catch {
    return 0;
  }
}

let stdinData = '';
process.stdin.setEncoding('utf8');
process.stdin.on('data', chunk => {
  if (stdinData.length < MAX_STDIN) stdinData += chunk.substring(0, MAX_STDIN - stdinData.length);
});

process.stdin.on('end', () => {
  try {
    let transcriptPath = null;
    try {
      const input = JSON.parse(stdinData);
      transcriptPath = input.transcript_path;
    } catch {
      transcriptPath = process.env.CLAUDE_TRANSCRIPT_PATH;
    }

    if (!transcriptPath || !fs.existsSync(transcriptPath)) {
      process.exit(0);
    }

    const messageCount = countUserMessages(transcriptPath);

    if (messageCount < MIN_SESSION_LENGTH) {
      process.exit(0);
    }

    const skillCount = countLearnedSkills();

    console.error(`[Learning] Session: ${messageCount} messages — consider /learn or /learn-eval to extract patterns`);
    if (skillCount > 0) {
      console.error(`[Learning] ${skillCount} learned skill(s) in ${LEARNED_SKILLS_DIR}`);
    }
  } catch {
    // Non-blocking.
  }

  process.exit(0);
});
