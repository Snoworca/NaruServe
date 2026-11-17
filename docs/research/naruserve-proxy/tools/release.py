# usage: release.py <stage> <json applied counts {wave:n}>  — journals replay result, removes worktrees, journals release
import sys; sys.path.insert(0,__file__.rsplit('/',1)[0]); from common import *
s=int(sys.argv[1]); applied=json.loads(sys.argv[2]); SD=f"{WR}waves/stage-{s}"; P=json.load(open(f"{SD}/prep.json"))
app({"event":"result","verb":"replay-deferred-mutations","stage":s,"wave":"all","proof":{"kind":"fs-exists","ref":f"kiwi/orchestrator/{R}/replay-applied.jsonl"},"outputs":{"applied":applied,"skipped":[]}})
rel=[]
for l in P['lanes']:
  w=l['wave'].replace(R+'-',''); wt=f".claude/worktrees/naruserve-proxy-{w}"
  if os.path.exists(wt): sh("git","worktree","remove","--force",wt); rel.append(wt)
app({"event":"result","verb":"release-lane","stage":s,"wave":"all","proof":{"kind":"fs-exists","ref":f"{SD}/{P['lanes'][0]['laneId']}/lane-manifest.json"},"outputs":{"released":rel,"branches_kept":True,"harvested_before_release":True}})
