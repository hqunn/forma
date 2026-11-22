# -*- coding: utf-8 -*-
"""Live preview for the report. Watch the Markdown, rebuild, reload the browser.

Why this exists rather than latexmk -pvc: latexmk is a Perl script and there is
no Perl on this machine. Why not LaTeX Workshop: it watches .tex, and the .tex
files here are generated from Markdown, so saving a chapter would not trigger
it. This needs nothing installed.

Run:
    python -I live.py            # from docs/report/latex
    python -I live.py --port 8751
    python -I live.py --no-open  # do not launch the browser

Then edit any file in docs/report/*.md and save. The page reloads itself, and
jumps to the chapter you just edited.

Ctrl-C to stop.
"""
import argparse
import http.server
import io
import json
import os
import re
import shutil
import socketserver
import subprocess
import sys
import threading
import time
import webbrowser

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.abspath(os.path.join(HERE, ".."))          # docs/report
LIVE = os.path.join(HERE, ".live")                        # served snapshot
MIKTEX = r"C:\Users\USER\AppData\Local\Programs\MiKTeX\miktex\bin\x64"

# Files whose modification triggers a rebuild.
WATCH_GLOBS = [
    (SRC, r"^ch\d+.*\.md$"),
    (HERE, r"^main\.tex$"),
    (os.path.join(HERE, "chapters", "front"), r"\.tex$"),
    (os.path.join(HERE, "chapters", "back"), r"\.tex$"),
    (os.path.join(HERE, "refs"), r"\.bib$"),
]

state = {
    "build": 0,            # increments on every successful build
    "status": "starting",  # starting | building | ok | error
    "errors": [],
    "pages": 0,
    "jump": 1,             # page to jump to after the next reload
    "at": "",
    "took": 0.0,
}
lock = threading.Lock()

# Serialises whole builds. `lock` guards the state dict for a few microseconds;
# this one guards the build itself, which is a different job.
#
# Without it, a /full request and the watcher can run pdflatex at the same
# time. /full invokes md2tex, md2tex rewrites chapters/*.tex, the watcher sees
# those mtimes move and starts its own one-pass build, and the two processes
# write main.aux concurrently. bibtex then reads a half-written aux, reports
# "I found no \\citation commands", and every citation in the PDF renders as
# [?]. Nothing fails loudly: the build says ok and the undefined-citation
# count is the only evidence. Observed 2026-11-03.
build_lock = threading.Lock()


# ------------------------------------------------------------------ build ---

def tool(name):
    """Absolute path to a MiKTeX executable.

    On Windows, CreateProcess resolves the executable from the PARENT process's
    PATH, not from the env dict passed to the child. Putting the MiKTeX
    directory in env["PATH"] is therefore not enough: the name has to be
    resolved here.
    """
    exe = os.path.join(MIKTEX, name + ".exe")
    if os.path.exists(exe):
        return exe
    found = shutil.which(name)
    if found:
        return found
    raise FileNotFoundError(
        "%s not found. Looked in %s and on PATH." % (name, MIKTEX))


def env_with_miktex():
    env = dict(os.environ)
    if os.path.isdir(MIKTEX):
        env["PATH"] = MIKTEX + os.pathsep + env.get("PATH", "")
    return env


def run(cmd, env):
    return subprocess.run(cmd, cwd=HERE, env=env, stdout=subprocess.DEVNULL,
                          stderr=subprocess.DEVNULL, timeout=300)


def log_errors():
    """Pull the error lines out of main.log. Exit code 0 is not enough."""
    path = os.path.join(HERE, "main.log")
    if not os.path.exists(path):
        return ["main.log missing: pdflatex did not run"]
    text = io.open(path, encoding="utf-8", errors="replace").read()
    out = []
    for m in re.finditer(r"(?m)^(! .*)$", text):
        line = m.group(1).strip()
        tail = text[m.end():m.end() + 400]
        loc = re.search(r"(?m)^l\.(\d+)(.*)$", tail)
        if loc:
            line += "   [line %s] %s" % (loc.group(1), loc.group(2).strip()[:80])
        out.append(line)
    for pat, label in (
        (r"LaTeX Warning: Reference `([^']+)' on page", "undefined reference"),
        (r"LaTeX Warning: Citation `([^']+)' on page", "undefined citation"),
    ):
        for m in re.finditer(pat, text):
            msg = "%s: %s" % (label, m.group(1))
            if msg not in out:
                out.append(msg)
    return out


def chapter_pages():
    """Map chapter number -> first page, read from main.toc."""
    path = os.path.join(HERE, "main.toc")
    pages = {}
    if not os.path.exists(path):
        return pages
    text = io.open(path, encoding="utf-8", errors="replace").read()
    for m in re.finditer(
            r"\\contentsline \{chapter\}\{\\chapternumberline \{(\d+)\}"
            r".*?\}\{(\d+)\}", text):
        pages[int(m.group(1))] = int(m.group(2))
    return pages


def page_count():
    path = os.path.join(LIVE, "preview.pdf")
    if not os.path.exists(path):
        return 0
    try:
        from pypdf import PdfReader
        return len(PdfReader(path).pages)
    except Exception:
        pass
    try:
        data = io.open(path, "rb").read()
        return len(re.findall(rb"/Type\s*/Page[^s]", data))
    except Exception:
        return 0


def build(changed, full=False):
    """md2tex, then pdflatex. One pass for speed, three plus bibtex when full.

    Holds `build_lock` for the whole run, so a watcher-triggered build and a
    /full request queue instead of racing over main.aux.
    """
    with build_lock:
        _build(changed, full)


def _build(changed, full=False):
    t0 = time.time()
    with lock:
        state["status"] = "building"
    env = env_with_miktex()

    try:
        if any(p.endswith(".md") for p in changed) or full:
            run([sys.executable, "-I", "md2tex.py"], env)

        pdflatex, bibtex = tool("pdflatex"), tool("bibtex")
        passes = 1 if not full else 3
        for i in range(passes):
            run([pdflatex, "-interaction=nonstopmode", "main.tex"], env)
            if full and i == 0:
                run([bibtex, "main"], env)
    except subprocess.TimeoutExpired:
        with lock:
            state.update(status="error", errors=["build timed out after 300s"],
                         at=time.strftime("%H:%M:%S"))
        return
    except FileNotFoundError as e:
        with lock:
            state.update(status="error", errors=[str(e)],
                         at=time.strftime("%H:%M:%S"))
        return

    errs = log_errors()
    pdf = os.path.join(HERE, "main.pdf")
    if os.path.exists(pdf):
        os.makedirs(LIVE, exist_ok=True)
        # Copy, so the browser never reads a half-written file.
        shutil.copyfile(pdf, os.path.join(LIVE, "preview.pdf"))

    # Jump to the chapter whose Markdown changed.
    jump = 1
    cps = chapter_pages()
    for p in changed:
        m = re.match(r"ch(\d+)", os.path.basename(p))
        if m and int(m.group(1)) in cps:
            jump = cps[int(m.group(1))]
            break

    with lock:
        state.update(
            build=state["build"] + 1,
            status="error" if errs else "ok",
            errors=errs[:12],
            pages=page_count(),
            jump=jump,
            at=time.strftime("%H:%M:%S"),
            took=round(time.time() - t0, 1),
        )


# ------------------------------------------------------------------ watch ---

def snapshot():
    out = {}
    for folder, pat in WATCH_GLOBS:
        if not os.path.isdir(folder):
            continue
        rx = re.compile(pat)
        for name in os.listdir(folder):
            if rx.search(name):
                p = os.path.join(folder, name)
                try:
                    out[p] = os.path.getmtime(p)
                except OSError:
                    pass
    return out


def watcher():
    """Never let this thread die. A dead watcher looks exactly like a slow
    build from the browser's side, which would be a confusing failure."""
    prev = snapshot()
    try:
        build(list(prev), full=True)
    except Exception as e:
        with lock:
            state.update(status="error", errors=["watcher: %r" % e],
                         at=time.strftime("%H:%M:%S"))
    while True:
        try:
            time.sleep(0.5)
            cur = snapshot()
            changed = [p for p, t in cur.items() if prev.get(p) != t]
            if changed:
                # Let a burst of saves settle before building.
                time.sleep(0.4)
                cur = snapshot()
                changed = [p for p, t in cur.items() if prev.get(p) != t]
                prev = cur
                build(changed)
        except Exception as e:
            with lock:
                state.update(status="error", errors=["watcher: %r" % e],
                             at=time.strftime("%H:%M:%S"))
            time.sleep(2)


# ------------------------------------------------------------------- page ---

PAGE = """<!doctype html>
<html lang="en"><head><meta charset="utf-8">
<title>FORMA report - live</title>
<style>
  :root { --ink:#16323D; --dim:#4A646F; --line:#C7D6DC; --bg:#F2F7F9;
          --warn:#E8663C; --warnbg:#FDF0EB; }
  * { box-sizing:border-box; }
  html,body { margin:0; height:100%; font:14px/1.5 system-ui,Segoe UI,sans-serif;
              color:var(--ink); background:var(--bg); }
  body { display:flex; flex-direction:column; }
  header { display:flex; align-items:center; gap:14px; padding:8px 14px;
           border-bottom:1px solid var(--line); background:#fff; flex:0 0 auto; }
  .dot { width:9px; height:9px; border-radius:50%; background:var(--dim); flex:0 0 auto; }
  .dot.ok { background:#2E9E6B; } .dot.err { background:var(--warn); }
  .dot.busy { background:#E3A008; animation:p 1s infinite; }
  @keyframes p { 50% { opacity:.3 } }
  h1 { font-size:14px; font-weight:600; margin:0; }
  .meta { color:var(--dim); font-size:12px; margin-left:auto; }
  button { font:inherit; font-size:12px; padding:4px 10px; border:1px solid var(--line);
           background:#fff; border-radius:4px; color:var(--ink); cursor:pointer; }
  button:hover { background:var(--bg); }
  #errs { display:none; background:var(--warnbg); border-bottom:1px solid var(--warn);
          padding:8px 14px; font-family:ui-monospace,Consolas,monospace; font-size:12px;
          max-height:28vh; overflow:auto; flex:0 0 auto; white-space:pre-wrap; }
  iframe { flex:1 1 auto; width:100%; border:0; background:#fff; }
</style></head><body>
<header>
  <span class="dot" id="dot"></span>
  <h1>FORMA report</h1>
  <button onclick="full()">Full rebuild</button>
  <span class="meta" id="meta">starting</span>
</header>
<div id="errs"></div>
<iframe id="pdf" src="about:blank"></iframe>
<script>
let seen = -1;
async function tick() {
  try {
    const s = await (await fetch('/status', {cache:'no-store'})).json();
    document.getElementById('dot').className =
      'dot ' + (s.status === 'ok' ? 'ok' : s.status === 'error' ? 'err' : 'busy');
    document.getElementById('meta').textContent =
      s.status === 'building' ? 'building...'
      : `build #${s.build} - ${s.pages} pages - ${s.took}s - ${s.at}`;
    const e = document.getElementById('errs');
    if (s.errors && s.errors.length) {
      e.style.display = 'block';
      e.textContent = s.errors.join('\\n');
    } else { e.style.display = 'none'; }
    if (s.build !== seen && s.status !== 'building') {
      seen = s.build;
      document.getElementById('pdf').src =
        '/preview.pdf?v=' + s.build + '#page=' + s.jump;
    }
  } catch (_) {}
}
function full() { fetch('/full', {cache:'no-store'}); }
setInterval(tick, 900); tick();
</script></body></html>
"""


class Handler(http.server.BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def _send(self, code, ctype, body, nocache=True):
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        if nocache:
            self.send_header("Cache-Control", "no-store, must-revalidate")
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        path = self.path.split("?")[0].split("#")[0]
        if path in ("/", "/index.html"):
            self._send(200, "text/html; charset=utf-8", PAGE.encode("utf-8"))
        elif path == "/status":
            with lock:
                body = json.dumps(state).encode("utf-8")
            self._send(200, "application/json", body)
        elif path == "/preview.pdf":
            p = os.path.join(LIVE, "preview.pdf")
            if not os.path.exists(p):
                self._send(404, "text/plain", b"not built yet")
                return
            self._send(200, "application/pdf", io.open(p, "rb").read())
        elif path == "/full":
            threading.Thread(target=build, args=([], True), daemon=True).start()
            self._send(200, "text/plain", b"ok")
        else:
            self._send(404, "text/plain", b"not found")


class Server(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=8750)
    ap.add_argument("--no-open", action="store_true")
    args = ap.parse_args()

    threading.Thread(target=watcher, daemon=True).start()
    url = "http://127.0.0.1:%d/" % args.port
    print("live preview:  %s" % url)
    print("watching:      %s\\ch*.md  and the hand-written .tex" % SRC)
    print("Ctrl-C to stop.")
    if not args.no_open:
        threading.Timer(1.2, lambda: webbrowser.open(url)).start()
    try:
        Server(("127.0.0.1", args.port), Handler).serve_forever()
    except KeyboardInterrupt:
        print("\nstopped.")


if __name__ == "__main__":
    main()
