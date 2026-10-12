"""Build the standalone index.html from app.html.

app.html is written in the claude.ai Artifact page format (no <html>/<head>/<body>).
This wraps it into a normal web page so it can be opened from GitHub Pages or a file.

    python3 sleep-cbti/build.py
"""
import pathlib
import re

here = pathlib.Path(__file__).parent
src = (here / "app.html").read_text(encoding="utf-8")

# Move the leading <title>, font <link>s and <style> into <head>.
head_parts = []
for pattern in (r"<title>.*?</title>\s*", r"(?:<link [^>]*>\s*)+", r"<style>.*?</style>\s*"):
    m = re.search(pattern, src, re.S)
    if m:
        head_parts.append(m.group(0).strip())
        src = src[: m.start()] + src[m.end():]

page = (
    "<!doctype html>\n"
    '<html lang="zh-Hant">\n<head>\n'
    '<meta charset="utf-8">\n'
    '<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">\n'
    '<meta name="theme-color" content="#16213a">\n'
    + "\n".join(head_parts)
    + "\n</head>\n<body>\n"
    + src.strip()
    + "\n</body>\n</html>\n"
)
(here / "index.html").write_text(page, encoding="utf-8")
print("wrote", here / "index.html")
