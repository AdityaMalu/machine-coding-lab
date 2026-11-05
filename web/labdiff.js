/*
 * Two editor helpers, no dependencies:
 *  - LabDiff.lines / LabDiff.render: a line diff (starter or your code -> reference solution) drawn like a code review.
 *  - LabDiff.parseDiagnostics / LabDiff.liveCheck: compiler errors as {file, line, col, message} for red squiggles,
 *    and an instant check for unbalanced brackets while you type.
 */
(function () {
  "use strict";

  const esc = (s) => String(s).replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));

  // ------------------------------------------------------------------ line diff

  /** [{op: "same"|"del"|"add", text, a, b}] with 1-based line numbers in the old (a) and new (b) text. */
  function lines(oldText, newText) {
    const A = oldText.replace(/\r/g, "").split("\n");
    const B = newText.replace(/\r/g, "").split("\n");
    // trim the common head and tail so the LCS table stays small
    let head = 0;
    while (head < A.length && head < B.length && A[head] === B[head]) head++;
    let tail = 0;
    while (tail < A.length - head && tail < B.length - head && A[A.length - 1 - tail] === B[B.length - 1 - tail]) tail++;
    const a = A.slice(head, A.length - tail), b = B.slice(head, B.length - tail);
    const n = a.length, m = b.length;
    const out = [];
    for (let i = 0; i < head; i++) out.push({ op: "same", text: A[i], a: i + 1, b: i + 1 });
    if (n * m > 4e6) {
      // too big to align: show it as a replacement
      a.forEach((t, i) => out.push({ op: "del", text: t, a: head + i + 1 }));
      b.forEach((t, j) => out.push({ op: "add", text: t, b: head + j + 1 }));
    } else {
      const w = m + 1;
      const L = new Uint32Array((n + 1) * w);
      for (let i = n - 1; i >= 0; i--) {
        for (let j = m - 1; j >= 0; j--) {
          L[i * w + j] = a[i] === b[j] ? L[(i + 1) * w + j + 1] + 1 : Math.max(L[(i + 1) * w + j], L[i * w + j + 1]);
        }
      }
      let i = 0, j = 0;
      while (i < n || j < m) {
        if (i < n && j < m && a[i] === b[j]) { out.push({ op: "same", text: a[i], a: head + i + 1, b: head + j + 1 }); i++; j++; }
        else if (j < m && (i === n || L[i * w + j + 1] >= L[(i + 1) * w + j])) { out.push({ op: "add", text: b[j], b: head + j + 1 }); j++; }
        else { out.push({ op: "del", text: a[i], a: head + i + 1 }); i++; }
      }
    }
    for (let k = tail; k > 0; k--) {
      out.push({ op: "same", text: A[A.length - k], a: A.length - k + 1, b: B.length - k + 1 });
    }
    // within a change block, removals come before additions (reads like a review tool)
    const sorted = [];
    for (let k = 0; k < out.length;) {
      if (out[k].op === "same") { sorted.push(out[k++]); continue; }
      const block = [];
      while (k < out.length && out[k].op !== "same") block.push(out[k++]);
      sorted.push(...block.filter((x) => x.op === "del"), ...block.filter((x) => x.op === "add"));
    }
    return sorted;
  }

  /** The changed middle of two similar lines, so the exact edit stands out. */
  function wordSpans(oldLine, newLine) {
    let p = 0;
    while (p < oldLine.length && p < newLine.length && oldLine[p] === newLine[p]) p++;
    let s = 0;
    while (s < oldLine.length - p && s < newLine.length - p && oldLine[oldLine.length - 1 - s] === newLine[newLine.length - 1 - s]) s++;
    const mark = (t) => esc(t.slice(0, p)) + (t.length - s > p ? `<mark>${esc(t.slice(p, t.length - s))}</mark>` : "") + esc(t.slice(t.length - s));
    // only worth it when most of the line is shared
    if (p + s < Math.min(oldLine.length, newLine.length) * 0.4) return null;
    return [mark(oldLine), mark(newLine)];
  }

  const CONTEXT = 3;

  /**
   * HTML for one file's diff. Unchanged stretches longer than a few lines fold into a "N unchanged lines" button
   * (data-fold), which the caller expands by swapping in the hidden lines.
   */
  function render(rows, opts = {}) {
    const html = [];
    let added = 0, removed = 0;
    // pair each removed line with the added line in the same position of its block, for in-line marks
    const pairs = new Map();
    for (let k = 0; k < rows.length;) {
      if (rows[k].op === "same") { k++; continue; }
      const start = k;
      while (k < rows.length && rows[k].op !== "same") k++;
      const dels = [], adds = [];
      for (let x = start; x < k; x++) (rows[x].op === "del" ? dels : adds).push(x);
      for (let x = 0; x < Math.min(dels.length, adds.length); x++) {
        const spans = wordSpans(rows[dels[x]].text, rows[adds[x]].text);
        if (spans) { pairs.set(dels[x], spans[0]); pairs.set(adds[x], spans[1]); }
      }
    }
    const line = (r, k) => {
      if (r.op === "add") added++;
      if (r.op === "del") removed++;
      const sign = r.op === "add" ? "+" : r.op === "del" ? "-" : " ";
      const body = pairs.get(k) ?? esc(r.text);
      return `<div class="dl ${r.op}"><span class="ln">${r.a ?? ""}</span><span class="ln">${r.b ?? ""}</span>`
        + `<span class="sg">${sign}</span><span class="tx">${body || " "}</span></div>`;
    };
    const changed = rows.map((r) => r.op !== "same");
    const near = rows.map((_, k) => {
      if (opts.full) return true;
      for (let x = Math.max(0, k - CONTEXT); x <= Math.min(rows.length - 1, k + CONTEXT); x++) if (changed[x]) return true;
      return false;
    });
    for (let k = 0; k < rows.length;) {
      if (near[k]) { html.push(line(rows[k], k)); k++; continue; }
      const start = k;
      while (k < rows.length && !near[k]) k++;
      const hidden = rows.slice(start, k).map((r, x) => line(r, start + x)).join("");
      if (k - start <= 2) { html.push(hidden); continue; }
      html.push(`<button class="dl fold" data-fold>⋯ ${k - start} unchanged lines</button><template>${hidden}</template>`);
    }
    return { html: html.join(""), added, removed };
  }

  // ------------------------------------------------------------------ compiler output -> diagnostics

  /**
   * Finds "file:line[:col]: [error|warning:] message" lines (javac, g++, go, Python's runner) and javac's caret
   * line under the source. `files` are the editable paths ("shop/OrderService.java"); output paths are matched to
   * them by suffix. Test files ("[tests] ...") are ignored: they can't be edited.
   */
  function parseDiagnostics(output, files) {
    const out = [];
    if (!output) return out;
    const text = output.replace(/\r/g, "");
    const rows = text.split("\n");
    const re = /^\s*(?:at\s+)?(\[tests\]\s*)?((?:[A-Za-z]:)?[\w.\/\\ -]*?\.(?:java|py|go|cpp|cc|cxx|hpp|h|properties|ya?ml)):(\d+)(?::(\d+))?(?::|\s|$)\s*(?:(fatal error|error|warning|note):\s*)?(.*)$/;
    const norm = (p) => p.replace(/\\/g, "/").replace(/^\.\//, "");
    const match = (p) => {
      const n = norm(p);
      return files.find((f) => f === n) || files.find((f) => n.endsWith("/" + f) || f.endsWith("/" + n))
        || files.find((f) => f.split("/").pop() === n.split("/").pop());
    };
    for (let i = 0; i < rows.length; i++) {
      const m = rows[i].match(re);
      if (!m || m[1]) continue;
      const file = match(m[2]);
      if (!file) continue;
      const sev = m[5] === "warning" ? "warning" : m[5] === "note" ? "note" : "error";
      if (sev === "note") continue;
      let col = m[4] ? parseInt(m[4], 10) : null;
      let message = (m[6] || "").trim();
      // javac: "File.java:12: error: msg" then the source line, then spaces and "^"
      if (!col) {
        for (let k = i + 1; k <= i + 3 && k < rows.length; k++) {
          const caret = rows[k].match(/^(\s*)\^\s*$/);
          if (caret) { col = caret[1].length + 1; break; }
        }
      }
      // Python's runner: "file:12: SyntaxError: msg"; keep the type in the message
      if (!message) message = (rows.find((r) => r.trim() && !re.test(r)) || "error").trim();
      // detail lines javac adds ("  symbol:   variable x")
      for (let k = i + 1; k <= i + 6 && k < rows.length; k++) {
        const s = rows[k].match(/^\s+(symbol|location|required|found|reason):\s*(.*)$/);
        if (s) message += `\n${s[1]}: ${s[2]}`;
        else if (re.test(rows[k])) break;
      }
      const line = parseInt(m[3], 10);
      if (!out.some((x) => x.file === file && x.line === line && x.message === message)) out.push({ file, line, col, message, severity: sev });
    }
    return out;
  }

  // ------------------------------------------------------------------ live bracket check

  const OPEN = { "(": ")", "[": "]", "{": "}" };
  const CLOSE = { ")": "(", "]": "[", "}": "{" };

  /**
   * Unbalanced or mismatched brackets, found with the editor's own tokenizer so strings and comments are skipped.
   * Returns [{line, col, endCol, message}] with 0-based lines and columns (CodeMirror positions).
   */
  function liveCheck(cm) {
    const problems = [];
    const stack = [];
    const n = cm.lineCount();
    if (n > 5000) return problems;
    for (let line = 0; line < n; line++) {
      const tokens = cm.getLineTokens(line, true);
      for (const t of tokens) {
        if (t.type && /string|comment|meta/.test(t.type)) continue;
        for (let i = 0; i < t.string.length; i++) {
          const c = t.string[i];
          const col = t.start + i;
          if (OPEN[c]) stack.push({ c, line, col });
          else if (CLOSE[c]) {
            let k = stack.length - 1;
            while (k >= 0 && stack[k].c !== CLOSE[c]) k--;
            if (k < 0) {
              problems.push({ line, col, endCol: col + 1, message: `Unexpected '${c}': there's no '${CLOSE[c]}' open here.` });
              continue;
            }
            // "(1 + 2;  }": the '(' was never closed, and the '}' closes the block as intended
            for (const s of stack.slice(k + 1)) {
              problems.push({ line: s.line, col: s.col, endCol: s.col + 1, message: `'${s.c}' is never closed. Expected '${OPEN[s.c]}' before line ${line + 1}.` });
            }
            stack.length = k;
          }
        }
      }
      if (problems.length > 20) return problems;
    }
    for (const s of stack.slice(-5)) {
      problems.push({ line: s.line, col: s.col, endCol: s.col + 1, message: `'${s.c}' is never closed. Expected '${OPEN[s.c]}'.` });
    }
    return problems;
  }

  window.LabDiff = { lines, render, parseDiagnostics, liveCheck };
})();
