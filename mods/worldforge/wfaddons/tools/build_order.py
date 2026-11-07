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
    deps[d] = {modid[m] for m in re.findall(r"'(wf_\w+)-\*\.jar'", txt) if m in modid and modid[m] != d}
order, seen, temp = [], set(), set()
def visit(d):
    if d in seen: return
    if d in temp: return          # цикл: игнорируем обратное ребро
    temp.add(d)
    for x in sorted(deps[d]): visit(x)
    temp.discard(d); seen.add(d); order.append(d)
for d in dirs: visit(d)
print(" ".join(order))
