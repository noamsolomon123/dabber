import json
import sys

F = sys.argv[1]
OUT = sys.argv[2]
raw = open(F, encoding="utf-8", errors="replace").read()


def extract(key):
    m = f'"{key}":"'
    i = raw.find(m)
    if i < 0:
        return ""
    i += len(m)
    # walk to the unescaped closing quote
    out = []
    k = i
    while k < len(raw):
        c = raw[k]
        if c == "\\":
            out.append(raw[k : k + 2])
            k += 2
            continue
        if c == '"':
            break
        out.append(c)
        k += 1
    s = "".join(out)
    # JSON-unescape
    return json.loads('"' + s + '"')


rep = extract("report")
extra = extract("npuExtra")
open(OUT, "w", encoding="utf-8").write(
    rep + "\n\n---\n\n# NPU research notes\n\n" + extra
)
print("wrote", OUT, len(rep), "chars report,", len(extra), "chars extra")
