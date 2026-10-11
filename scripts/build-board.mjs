#!/usr/bin/env node
// Build BOARD.md from this repo's GitHub Issues.
// Each issue is one task. Status comes from a `status:*` label (closed = done/canceled).
// Claude Code session links are collected from the issue body and comments.
//
// Env: GITHUB_TOKEN, GITHUB_REPOSITORY (owner/name)
// Offline test: BOARD_FIXTURE=path/to/fixture.json (array of issues, each with a `comments` array of bodies)

import { readFileSync, writeFileSync } from "node:fs";

const STATUSES = [
  { key: "in-progress", title: "🔨 進行中" },
  { key: "in-review", title: "👀 待你確認" },
  { key: "blocked", title: "⛔ 卡住" },
  { key: "todo", title: "📋 待辦" },
  { key: "backlog", title: "💡 想法（未核准）" },
];
const DONE_LIMIT = 10;
const SESSION_RE = /https:\/\/claude\.ai\/code\/session_[A-Za-z0-9]+/g;

const repo = process.env.GITHUB_REPOSITORY;
const token = process.env.GITHUB_TOKEN;

async function api(path) {
  const res = await fetch(`https://api.github.com${path}`, {
    headers: {
      Authorization: `Bearer ${token}`,
      Accept: "application/vnd.github+json",
      "X-GitHub-Api-Version": "2022-11-28",
    },
  });
  if (!res.ok) throw new Error(`${res.status} ${path}: ${await res.text()}`);
  return res.json();
}

async function fetchIssues() {
  if (process.env.BOARD_FIXTURE) {
    return JSON.parse(readFileSync(process.env.BOARD_FIXTURE, "utf8"));
  }
  const issues = [];
  for (let page = 1; ; page++) {
    const batch = await api(`/repos/${repo}/issues?state=all&per_page=100&page=${page}&sort=updated`);
    issues.push(...batch.filter((i) => !i.pull_request));
    if (batch.length < 100) break;
  }
  const open = issues.filter((i) => i.state === "open");
  const done = issues.filter((i) => i.state === "closed" && i.state_reason === "completed").slice(0, DONE_LIMIT);
  const shown = [...open, ...done];
  for (const issue of shown) {
    issue.comments = issue.comments > 0
      ? (await api(`/repos/${repo}/issues/${issue.number}/comments?per_page=100`)).map((c) => c.body ?? "")
      : [];
  }
  return shown;
}

function statusOf(issue) {
  if (issue.state === "closed") return "done";
  const labels = issue.labels.map((l) => (typeof l === "string" ? l : l.name));
  const found = STATUSES.find((s) => labels.includes(`status:${s.key}`));
  return found ? found.key : "todo";
}

function field(body, name) {
  const m = (body ?? "").match(new RegExp(`^\\s*[-*]\\s*${name}\\s*[:：]\\s*(.+?)\\s*$`, "mi"))
    // issue-form format: "### 專案 repo\n\nowner/name"
    ?? (body ?? "").match(new RegExp(`^###[^\\n]*${name}[^\\n]*\\n+([^\\n#]+)`, "mi"));
  const value = m?.[1]?.replace(/`/g, "").trim();
  return value && !/^([（(]?(無|未定|none|-)[)）]?|_No response_)$/i.test(value) ? value : "";
}

function sessionsOf(issue) {
  const text = [issue.body ?? "", ...(issue.comments ?? [])].join("\n");
  return [...new Set(text.match(SESSION_RE) ?? [])];
}

const esc = (s) => String(s).replace(/\|/g, "\\|");
const day = (iso) => (iso ?? "").slice(0, 10);

function row(issue) {
  const sessions = sessionsOf(issue);
  const latest = sessions.at(-1);
  const sessionCell = latest
    ? `[最新對話](${latest})${sessions.length > 1 ? `（共 ${sessions.length} 個）` : ""}`
    : "—";
  const repoName = field(issue.body, "repo");
  const branch = field(issue.body, "branch");
  return `| [#${issue.number}](${issue.html_url}) ${esc(issue.title)} | ${esc(repoName || "—")} | ${branch ? `\`${esc(branch)}\`` : "—"} | ${sessionCell} | ${day(issue.updated_at)} |`;
}

function render(issues) {
  const head = "| 任務 | 專案 | 分支 | 對話 | 更新 |\n| --- | --- | --- | --- | --- |";
  const out = [
    "# 任務板",
    "",
    "> 這個檔案由 GitHub Actions 依 Issues 自動產生，請勿手動編輯。",
    "> 每個任務 = 一個 Issue；「對話」欄是最後一個處理它的 Claude Code 對話。",
    "",
  ];
  for (const s of [...STATUSES, { key: "done", title: `✅ 最近完成（${DONE_LIMIT} 筆內）` }]) {
    const list = issues.filter((i) => statusOf(i) === s.key);
    out.push(`## ${s.title}（${list.length}）`, "");
    out.push(list.length ? [head, ...list.map(row)].join("\n") : "_（無）_", "");
  }
  return out.join("\n");
}

const issues = await fetchIssues();
const md = render(issues);
const outPath = process.env.BOARD_OUT ?? "BOARD.md";
writeFileSync(outPath, md);
console.log(`wrote ${outPath} (${issues.length} issues)`);
