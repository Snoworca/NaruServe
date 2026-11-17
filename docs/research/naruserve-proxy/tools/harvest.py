# usage: harvest.py <stage>  — copies lane .kiwi artefacts into stage dir, prints write-set check, makes replay plans
import sys,shutil; sys.path.insert(0,__file__.rsplit('/',1)[0]); from common import *
s=int(sys.argv[1]); SD=f"{WR}waves/stage-{s}"; P=json.load(open(f"{SD}/prep.json"))
for l in P['lanes']:
  w=l['wave'].replace(R+'-',''); lid=l['laneId']; wt=f".claude/worktrees/naruserve-proxy-{w}"; H=f"{SD}/{lid}"
  os.makedirs(H,exist_ok=True)
  for f in ("lane-manifest.json","deferred-srs-mutations.jsonl"): shutil.copy(f"{wt}/.kiwi/{f}",H)
  shutil.copytree(f"{wt}/.kiwi/sessions",f"{H}/kiwi-sessions",dirs_exist_ok=True)
  base=P['base'][w]; files=sh("git","-C",wt,"diff","--name-only",f"{base}..HEAD").split()
  print(w,"head",sh("git","-C",wt,"rev-parse","HEAD")[:7],"ancestor-of-base-ok", subprocess.run(["git","-C",wt,"merge-base","--is-ancestor",base,"HEAD"]).returncode==0)
  print("  outside write set:",[f for f in files if f not in l['writeSet']])
  print("  spec/research touched:",[f for f in files if f.startswith(('docs/spec','docs/sds','docs/prd','docs/research','kiwi/'))])
  print("  uncommitted:",sh("git","-C",wt,"status","--short").replace("?? .kiwi/","").strip())
  print("  commits:",sh("git","-C",wt,"log","--format=%h %(trailers:key=REQ,valueonly,separator=) %(trailers:key=Commit-Kind,valueonly,separator=) %(trailers:key=Orch-Lane,valueonly,separator=)",f"{base}..HEAD"))
  pl=sh("speckiwi","orchestrate","replay","plan","--queue",f"{H}/deferred-srs-mutations.jsonl","--json",check=False)
  open(f"{H}/replay-plan.json","w").write(pl); d=json.loads(pl); print("  replay plan",d.get('ok'),len(d.get('calls',[])),"refusals",d.get('inputRefusals'))
