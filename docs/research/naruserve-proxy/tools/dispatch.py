# usage: dispatch.py <stage> <partition verdict text>
import sys; sys.path.insert(0,__file__.rsplit('/',1)[0]); from common import *
s=int(sys.argv[1]); verdict=sys.argv[2]; SD=f"{WR}waves/stage-{s}"; P=json.load(open(f"{SD}/prep.json")); N=nodes()
lanes=P['lanes']; ld=P['lock_digest']
md=[f"# Stage {s} partition — {R}","",f"Lane cap 4. Lanes dispatched in stage {s}:",""]+[f"- `{l['laneId']}` — {l['wave']}, {len(l['writeSet'])} paths in its write set" for l in lanes]+["",f"Verdict: pass — {verdict}",""]
open(f"{SD}/partition.md","w").write("\n".join(md))
app({"event":"intent","verb":"review-partition","stage":s,"inputs_digest":"sha256:"+ld})
app({"event":"result","verb":"review-partition","stage":s,"proof":{"kind":"digest","ref":f"{SD}/lanes.lock.json@sha256:{ld}"},"partition_review":{"verdict":"pass","lane_plan_digest":"sha256:"+ld,"document":f"{SD}/partition.md","decided_by":"recommended option (user standing instruction)"}})
st=sh("git","status","--porcelain","docs/spec","docs/sds")
for i,l in enumerate(lanes,1):
  w=l['wave'].replace(R+'-',''); n=int(w.split('-')[1]); lid=l['laneId']
  wt=f"{ROOT}/.claude/worktrees/naruserve-proxy-{w}"; br=f"kiwi/orch/{R}/{lid}"; base=P['base'][w]
  if not os.path.exists(wt): sh("git","worktree","add","-b",br,wt,base)
  node=N[w]; rq=reqs_of(node); q=f"{wt}/.kiwi/deferred-srs-mutations.jsonl"
  card={"run_id":R,"wave":w,"wave_number":n,"stage":s,"lane_id":lid,"lane_index":i,"worktree":wt,"branch":br,"base_sha":base,
   "lane_plan":f"{ROOT}/{SD}/lanes.lock.json","write_set":l['writeSet'],"requirements":rq,"node_title":node['title'],"done_when":node.get('done_when'),
   "verification_cmd":"./gradlew -PtinyHttpdFromMaven=true build","regression_baseline":f"{ROOT}/{WR}waves/regression-baseline.json",
   "queue":q,"manifest":f"{wt}/.kiwi/lane-manifest.json","trailers":{"Orch-Run":R,"Orch-Wave":str(n),"Orch-Stage":str(s),"Orch-Lane":lid},
   "kiwi_pm_args":f"--req-filter {{REQ}} --run-id {R}-{w}-{{req}} --session-suffix w{n}s{s}l{i} --no-final --no-pipeline-emit --commit-lane-work --defer-srs-mutation {q} --regression-baseline {ROOT}/{WR}waves/regression-baseline.json --loops 20",
   "review_args":f"--base {base} --head HEAD --req-filter {','.join(rq)} --no-pipeline-emit --keep-clean-code --loops 20",
   "prd":f"{ROOT}/docs/prd/2026-11-14.naruserve-proxy.prd.md","design":f"{ROOT}/{WR}design/00.design.md","brief":f"{ROOT}/{WR}waves/00.worker-brief.md"}
  cp=f"{SD}/{lid}.dispatch.json"; json.dump(card,open(cp,"w"),indent=1,ensure_ascii=False)
  app({"event":"intent","verb":"dispatch-lane","wave":w,"order":n,"target":w,"status":"in_progress","stage":s,"lane":lid,"inputs_digest":"sha256:"+ld,"sds_id":f"{R}-{w}","isolation":{"base_sha":base,"worktree":os.path.relpath(wt,ROOT)},"spec_sds_status_before":st,"summary":f"dispatch {w} worker"})
  print("CARD",cp)
