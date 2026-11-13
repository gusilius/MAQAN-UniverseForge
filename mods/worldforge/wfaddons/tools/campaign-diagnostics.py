#!/usr/bin/env python3
import argparse, json, re
from pathlib import Path
from datetime import datetime, timezone
p=argparse.ArgumentParser()
p.add_argument("--work",required=True); p.add_argument("--output",required=True)
p.add_argument("--exit-code",required=True,type=int); p.add_argument("--stage",required=True)
p.add_argument("--run-id",default="local"); p.add_argument("--commit",default="unknown"); p.add_argument("--seed",default="unknown")
a=p.parse_args(); work=Path(a.work); out=Path(a.output); out.mkdir(parents=True,exist_ok=True)
src=work/"server.log"; log=out/"server.log"
if src.is_file(): log.write_bytes(src.read_bytes())
installer=work/"install.log"
if installer.is_file(): (out/"forge-install.log").write_bytes(installer.read_bytes())
content=log.read_text(encoding="utf-8",errors="replace") if log.exists() else ""
rules=[("watchdog",r"Watchdog|A single server tick took|Considering it to be crashed"),("out_of_memory",r"OutOfMemoryError|Java heap space|GC overhead limit exceeded"),("native_failure",r"UnsatisfiedLinkError|EXCEPTION_ACCESS_VIOLATION|worldforge_worldsim"),("timeout",r"wall-clock timeout|did not reach stable four-generation acceptance"),("startup_failure",r"Mod loading has failed|Failed to start|Exception in thread \"main\""),("preflight_failure",r"Campaign map must retain|Campaign requires|Native WorldSim engine is unavailable|Unknown or incomplete command"),("exception",r"Caused by:|Exception")]
detected=[n for n,pat in rules if re.search(pat,content,re.I)]
if a.exit_code==0: outcome="passed"
elif "watchdog" in detected: outcome="failed_watchdog"
elif "out_of_memory" in detected: outcome="failed_out_of_memory"
elif "native_failure" in detected: outcome="failed_native"
elif "timeout" in detected: outcome="failed_timeout"
elif "startup_failure" in detected: outcome="failed_startup"
elif "preflight_failure" in detected: outcome="failed_preflight"
else: outcome="failed_unclassified"
lines=content.splitlines(); excerpt=[]; seen=set()
for i,line in enumerate(lines):
    if re.search(r"Watchdog|A single server tick took|Considering it to be crashed|OutOfMemoryError|Exception|Caused by:|UnsatisfiedLinkError|ERROR|FATAL",line,re.I):
        for item in lines[max(0,i-3):min(len(lines),i+36)]:
            if item not in seen: seen.add(item); excerpt.append(item)
data={"schema_version":1,"recorded_at_utc":datetime.now(timezone.utc).isoformat(),"run_id":a.run_id,"commit":a.commit,"map":"planetos","map_seed":a.seed,"forge_version":"1.20.1-47.3.0","stage_at_exit":a.stage,"exit_code":a.exit_code,"outcome":outcome,"detected_signatures":detected,"server_log":"server.log" if log.exists() else None,"install_log":"forge-install.log" if (out/"forge-install.log").exists() else None,"diagnostic_excerpt":excerpt[-600:],"server_log_line_count":len(lines)}
(out/"diagnostics.json").write_text(json.dumps(data,ensure_ascii=False,indent=2)+"\n",encoding="utf-8")
md=["# Campaign diagnostics","",f"Outcome: {outcome}",f"Run: {a.run_id}",f"Commit: {a.commit}",f"Map seed: {a.seed}",f"Stage: {a.stage}",f"Exit code: {a.exit_code}",f"Signatures: {', '.join(detected) if detected else 'none'}","","## Evidence","- diagnostics.json: structured result and extracted error context.","- server.log: complete server log when available.","- forge-install.log: installer output when available.","","## Diagnostic excerpt","", "    "+("\n    ".join(excerpt[-250:]) if excerpt else "No matching signature; inspect complete logs.")]
(out/"diagnostics.md").write_text("\n".join(md)+"\n",encoding="utf-8")
print("CAMPAIGN_DIAGNOSTICS="+str(out/"diagnostics.json")); print("CAMPAIGN_OUTCOME="+outcome)
