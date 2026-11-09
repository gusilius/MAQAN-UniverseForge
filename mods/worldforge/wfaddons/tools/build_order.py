#!/usr/bin/env python3
"""Порядок сборки аддонов: родители и мягкие зависимости (compileOnly wf_*-*.jar в build.gradle) раньше потомков."""
import os, re, sys
root = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
dirs = sorted(d for d in os.listdir(root) if d.startswith("wf-") and os.path.isdir(os.path.join(root, d)))
modid = {}
for d in dirs:
    p = os.path.join(root, d, "gradle.properties")
    if os.path.exists(p):
        m = re.search(r"^mod_id=(\S+)", open(p, encoding="utf-8").read(), re.M)
        if m: modid[m.group(1)] = d
deps = {}
for d in dirs:
    p = os.path.join(root, d, "build.gradle")
    txt = open(p, encoding="utf-8").read() if os.path.exists(p) else ""
    # Gradle dependency includes occur in both single- and double-quoted forms.
    # Accept only known addon mod ids, so unrelated jar patterns cannot enter the graph.
    referenced = re.findall(r"""['"](wf_[A-Za-z0-9_]+)-\*\.jar['"]""", txt)
    deps[d] = {modid[m] for m in referenced if m in modid and modid[m] != d}
order, state, stack = [], {}, []

def visit(d):
    status = state.get(d, 0)
    if status == 2:
        return
    if status == 1:
        cycle_start = stack.index(d)
        cycle = stack[cycle_start:] + [d]
        raise RuntimeError("Циклическая зависимость аддонов: " + " -> ".join(cycle))
    state[d] = 1
    stack.append(d)
    for dependency in sorted(deps[d]):
        visit(dependency)
    stack.pop()
    state[d] = 2
    order.append(d)

try:
    for d in dirs:
        visit(d)
except RuntimeError as error:
    print(f"ERROR: {error}", file=sys.stderr)
    sys.exit(1)

if len(order) != len(dirs):
    missing = sorted(set(dirs) - set(order))
    print("ERROR: аддоны не попали в порядок сборки: " + ", ".join(missing), file=sys.stderr)
    sys.exit(1)

if "--deps" in sys.argv:
    for d in order:
        print(d + "|" + ",".join(sorted(deps[d])))
else:
    print(" ".join(order))
