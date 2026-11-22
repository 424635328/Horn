import re
import pathlib

ok = True
for name in ["README.md", "README_EN.md"]:
    text = pathlib.Path(name).read_text(encoding="utf-8")
    blocks = re.findall(r"```mermaid\n(.*?)```", text, re.S)
    print(name, "mermaid blocks:", len(blocks))
    for i, block in enumerate(blocks):
        for ln, line in enumerate(block.splitlines(), 1):
            s = line.strip()
            if not s or s.startswith("%%"):
                continue
            if s.count('"') % 2 != 0:
                ok = False
                print("  UNBALANCED QUOTE", i, ln, s)
            head = s.split()[0]
            if not re.match(r"^(graph|flowchart|sequenceDiagram|stateDiagram|classDiagram|erDiagram|gantt|pie|journey|timeline|mindmap)$", head) and \
               not re.match(r"^[A-Za-z0-9_\"<{\[(]", s):
                ok = False
                print("  SUSPICIOUS LINE", i, ln, s)
print("mermaid sanity:", "PASS" if ok else "FAIL")
