"""Reads a uiautomator XML dump on stdin, prints: centerX, centerY, flags, label (tab-separated).
With an argument, prints only the best match for that text: exact label first, then substring;
clickable nodes win ties."""
import re
import sys
import xml.etree.ElementTree as ET

root = ET.fromstring(sys.stdin.read())
rows = []
for n in root.iter("node"):
    label = " ".join((n.get("text") or n.get("content-desc") or "").split())
    checkable = n.get("checkable") == "true"
    if not label and not checkable:
        continue
    if not label:
        label = "[switch]"
    b = re.findall(r"\d+", n.get("bounds", ""))
    if len(b) != 4:
        continue
    x1, y1, x2, y2 = map(int, b)
    clickable = n.get("clickable") == "true"
    flags = []
    if clickable:
        flags.append("clickable")
    if checkable:
        flags.append("checked" if n.get("checked") == "true" else "unchecked")
    rows.append(((x1 + x2) // 2, (y1 + y2) // 2, ",".join(flags) or "-", label, clickable))

if len(sys.argv) > 1:
    want = sys.argv[1].lower()
    def rank(r):
        label = r[3].lower()
        exact = 0 if label == want else 1
        contains = 0 if want in label else 1
        return (contains, exact, 0 if r[4] else 1, len(label))
    hits = [r for r in rows if want in r[3].lower()]
    if not hits:
        sys.exit(1)
    rows = [min(hits, key=rank)]

for x, y, flags, label, _ in rows:
    print(f"{x}\t{y}\t{flags}\t{label}")
