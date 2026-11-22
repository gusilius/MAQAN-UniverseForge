#!/usr/bin/env python3
import argparse, json, re
from pathlib import Path
import shutil
from datetime import datetime, timezone
p=argparse.ArgumentParser()
p.add_argument("--work",required=True); p.add_argument("--output",required=True)
p.add_argument("--exit-code",required=True,type=int); p.add_argument("--server-exit-code",default="not_observed"); p.add_argument("--termination",default="unknown"); p.add_argument("--stage",required=True)
p.add_argument("--run-id",default="local"); p.add_argument("--commit",default="unknown"); p.add_argument("--seed",default="unknown"); p.add_argument("--map",default="unknown")
a=p.parse_args(); work=Path(a.work); out=Path(a.output); out.mkdir(parents=True,exist_ok=True)
src=work/"server.log"; log=out/"server.log"
if src.is_file(): log.write_bytes(src.read_bytes())
survey_log=out/"survey-bot.log"
survey_content=survey_log.read_text(encoding="utf-8",errors="replace") if survey_log.is_file() else ""
installer=work/"install.log"
if installer.is_file(): (out/"forge-install.log").write_bytes(installer.read_bytes())
crash_dir=work/"crash-reports"
if crash_dir.is_dir(): shutil.copytree(crash_dir,out/"crash-reports",dirs_exist_ok=True)
for dump in work.glob("thread-dump-*.txt"): shutil.copy2(dump,out/dump.name)
for listing in work.glob("jcmd-processes-*.txt"): shutil.copy2(listing,out/listing.name)
for pattern in ("core-jcmd-*.txt", "core-heap-*.txt", "core-process-*.json"):
    for metric_file in work.glob(pattern): shutil.copy2(metric_file, out/metric_file.name)
for pattern in ("hs_err_pid*.log", "java_pid*.hprof", "gc-*.log"):
    for diagnostic_file in work.glob(pattern): shutil.copy2(diagnostic_file, out/diagnostic_file.name)
profile_src=work/"performance-profile.json"
if profile_src.is_file() and profile_src.resolve() != (out/"performance-profile.json").resolve():
    shutil.copy2(profile_src,out/"performance-profile.json")
content=log.read_text(encoding="utf-8",errors="replace") if log.exists() else ""
rules=[("watchdog",r"Watchdog|A single server tick took|Considering it to be crashed"),("out_of_memory",r"(?:java\.)?lang\.OutOfMemoryError|OutOfMemoryError|GC overhead limit exceeded"),("native_failure",r"UnsatisfiedLinkError|EXCEPTION_ACCESS_VIOLATION|EXCEPTION_ILLEGAL_INSTRUCTION|SIGSEGV|SIGBUS"),("timeout",r"wall-clock timeout|did not reach stable four-generation acceptance"),("startup_failure",r"Mod loading has failed|Failed to start|Exception in thread \"main\""),("preflight_failure",r"Campaign map must retain|Campaign requires|Native WorldSim engine is unavailable|Unknown or incomplete command"),("exception",r"Caused by:|Exception")]
detected=[n for n,pat in rules if re.search(pat,content,re.I)]
client_incompatible=bool(re.search(r"requires Forge to be installed on the client|Forge.*installed on the client",survey_content,re.I))
client_failed=bool(re.search(r"SURVEY_BOT_(?:ERROR|KICKED|DISCONNECTED)|survey player client exited before joining",survey_content,re.I))
if a.exit_code==0 and a.server_exit_code=="0" and a.termination=="graceful_stop": outcome="passed"
elif a.exit_code==0 and (a.server_exit_code!="0" or a.termination!="graceful_stop"): outcome="failed_server_shutdown"
elif a.exit_code==0 and a.server_exit_code=="not_observed": outcome="inconclusive_server_exit_unobserved"
elif client_incompatible: outcome="failed_test_client_incompatible"
elif client_failed: outcome="failed_test_client_connection"
elif "watchdog" in detected: outcome="failed_server_watchdog"
elif "out_of_memory" in detected: outcome="failed_server_oom"
elif "native_failure" in detected: outcome="failed_server_native"
elif "timeout" in detected: outcome="failed_timeout"
elif "startup_failure" in detected: outcome="failed_server_startup"
elif "preflight_failure" in detected: outcome="failed_test_preflight"
elif a.termination.lower() in ("runner_killed","process_killed","external_termination"): outcome="failed_infrastructure_termination"
elif a.exit_code != 0 and re.search(r"curl:|Could not resolve host|connection reset|Forge installer download|installer.jar", (installer.read_text(encoding="utf-8",errors="replace") if installer.exists() else ""), re.I): outcome="failed_infrastructure"
elif a.exit_code != 0 and detected: outcome="failed_server_exception"
elif a.exit_code != 0: outcome="failed_test_or_unclassified"
else: outcome="inconclusive"
rejected_matches = re.findall(r"proposal\s+([^\s]+)\s+rejected:\s*(.+)", content, re.I)
rejected_reasons = {}
for _proposal_id, reason in rejected_matches:
    normalized = reason.strip()[:240] or "unspecified"
    rejected_reasons[normalized] = rejected_reasons.get(normalized, 0) + 1
lines=content.splitlines(); excerpt=[]; seen=set()
for i,line in enumerate(lines):
    if re.search(r"Watchdog|A single server tick took|Considering it to be crashed|OutOfMemoryError|Exception|Caused by:|UnsatisfiedLinkError|ERROR|FATAL",line,re.I):
        for item in lines[max(0,i-3):min(len(lines),i+36)]:
            if item not in seen: seen.add(item); excerpt.append(item)
data={"schema_version":2,"survey_client_log":"survey-bot.log" if survey_log.is_file() else None,"survey_client_incompatible":client_incompatible,"recorded_at_utc":datetime.now(timezone.utc).isoformat(),"run_id":a.run_id,"commit":a.commit,"map":a.map,"map_seed":a.seed,"forge_version":"1.20.1-47.3.0","stage_at_exit":a.stage,"exit_code":a.exit_code,"server_exit_code":a.server_exit_code,"termination_evidence":a.termination,"outcome":outcome,"detected_signatures":detected,"server_log":"server.log" if log.exists() else None,"install_log":"forge-install.log" if (out/"forge-install.log").exists() else None,"crash_reports":[str(x.relative_to(out)) for x in (out/"crash-reports").glob("*.txt")] if (out/"crash-reports").exists() else [],"thread_dump_count":len(list(out.glob("thread-dump-*.txt"))),"core_process_samples":[x.name for x in out.glob("core-process-*.json")],"core_heap_samples":[x.name for x in out.glob("core-heap-*.txt")],"jvm_crash_logs":[x.name for x in out.glob("hs_err_pid*.log")],"heap_dumps":[x.name for x in out.glob("java_pid*.hprof")],"gc_logs":[x.name for x in out.glob("gc-*.log")],"rejected_proposals_total":len(rejected_matches),"rejected_proposal_reasons":dict(sorted(rejected_reasons.items(),key=lambda item:(-item[1],item[0]))),"performance_profile":"performance-profile.json" if (out/"performance-profile.json").is_file() else None,"diagnostic_excerpt":excerpt[-600:],"server_log_line_count":len(lines),"survey_client_excerpt":survey_content.splitlines()[-80:]}
(out/"diagnostics.json").write_text(json.dumps(data,ensure_ascii=False,indent=2)+"\n",encoding="utf-8")
md=["# Runtime diagnostics","",f"Outcome: {outcome}",f"Run: {a.run_id}",f"Commit: {a.commit}",f"Map: {a.map}",f"Map seed: {a.seed}",f"Stage: {a.stage}",f"Script exit code: {a.exit_code}",f"Server JVM exit code: {a.server_exit_code}",f"Termination evidence: {a.termination}",f"Signatures: {', '.join(detected) if detected else 'none'}","","## Evidence","- diagnostics.json: structured result and extracted error context.","- server.log: complete server log when available.","- survey-bot.log: virtual-client protocol and join diagnostics.","- forge-install.log: installer output when available.","- crash-reports/: Forge crash reports, including the server-thread stack when available.","- thread-dump-*.txt: periodic JVM thread snapshots collected while the runtime was running.",
"- hs_err_pid*.log: fatal JVM/native crash reports.",
"- java_pid*.hprof: heap dumps captured on OutOfMemoryError.",
"- gc-*.log: JVM garbage-collection timeline.",
"- rejected_proposal_reasons: aggregated rejection messages extracted from the server log.",
"- performance-profile.json: bounded rolling timing samples for the WorldSim server-tick and decision pipeline.","","## Diagnostic excerpt","", "    "+("\n    ".join(excerpt[-250:]) if excerpt else "No matching signature; inspect complete logs.")]
(out/"diagnostics.md").write_text("\n".join(md)+"\n",encoding="utf-8")
print("RUNTIME_DIAGNOSTICS="+str(out/"diagnostics.json")); print("RUNTIME_OUTCOME="+outcome)
