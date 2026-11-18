# usage: stageprep.py <stage> <wave,...> <readiness reason>
import sys; sys.path.insert(0,__file__.rsplit('/',1)[0]); from common import *
s=int(sys.argv[1]); waves=sys.argv[2].split(','); reason=sys.argv[3]
SD=f"{WR}waves/stage-{s}"; gd=sha(G); snap=f"{SD}/readiness-snapshot.json"; N=nodes()
viol=[]
for w in waves:
  for q in reqs_of(N[w]):
    o=json.loads(sh("speckiwi","orchestrate","readiness","check","--target","0.12.0","--snapshot",snap,"--req",q,"--json",check=False))
    for v in o.get('violations',[]): viol.append({"wave":w,**{k:v.get(k) for k in ('id','status','stability','hardDependenciesSatisfied','lifecycleReady','evidenceDrift','ownershipVerified')}})
print("readiness violations:",json.dumps(viol))
if any((not v['lifecycleReady']) or v['evidenceDrift'] or (not v['ownershipVerified']) for v in viol): print("NOT READY"); sys.exit(2)
app({"event":"intent","verb":"derive-readiness","inputs_digest":"sha256:"+gd,"stage":s})
app({"event":"result","verb":"derive-readiness","stage":s,"proof":{"kind":"digest","ref":f"{snap}@sha256:{sha(snap)}"},"outputs":{"waves":waves,"tool_violations":viol,"verdict":"ready","reason":reason}})
base={}
for i,w in enumerate(waves):
  n=int(w.split('-')[1])
  app({"event":"intent","verb":"commit-wave-inputs","wave":w,"inputs_digest":"sha256:"+gd})
  c=commit(f"chore(orchestrator): wave {n} inputs for stage {s}",[("Orch-Run",R),("Orch-Verb","commit-wave-inputs"),("Orch-Wave",n),("Orch-Stage",s)],[snap] if i==0 else [],allow_empty=True)
  base[w]=c
  app({"event":"result","verb":"commit-wave-inputs","wave":w,"proof":{"kind":"git-trailer","ref":f"{c} Orch-Run={R} Orch-Verb=commit-wave-inputs Orch-Wave={n}"},"isolation":{"base_sha":c}})
lock=f"{SD}/lanes.lock.json"
app({"event":"intent","verb":"freeze-lane-plan","stage":s,"inputs_digest":"sha256:"+gd})
o=json.loads(sh("speckiwi","orchestrate","schedule","waves","--waves",G,"--run-id",R,"--lanes","4","--out",lock,"--json",check=False))
if not o.get('ok'): print(json.dumps(o)[:1500]); sys.exit(3)
L=json.load(open(lock)); planned=[l['wave'].replace(R+'-','') for l in L['lanes'] if l['stage']==1]
print("lock first-stage lanes:",planned)
if sorted(planned)!=sorted(waves): print("MISMATCH"); sys.exit(4)
lc=commit(f"chore(orchestrator): stage {s} lane plan",[("Orch-Run",R),("Orch-Verb","freeze-lane-plan"),("Orch-Stage",s)],[lock])
fr=f"{SD}/lanes.freeze.json"
o=json.loads(sh("speckiwi","orchestrate","freeze","lanes","--body",lock,"--document",lock,"--head",lc,"--run-id",R,"--out",fr,"--json",check=False))
if not o.get('ok'): print(json.dumps(o)[:1500]); sys.exit(5)
ld=sha(lock)
app({"event":"result","verb":"freeze-lane-plan","stage":s,"proof":{"kind":"digest","ref":f"{lock}@sha256:{ld}"},"outputs":{"lock":lock,"freeze":f"{fr}@sha256:{sha(fr)}","commit":lc,"dispatch":waves}})
json.dump({"base":base,"lock_digest":ld,"lock_commit":lc,"lanes":[l for l in L['lanes'] if l['stage']==1]},open(f"{SD}/prep.json","w"),indent=1)
print("OK",json.dumps(base))
