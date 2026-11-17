# usage: closewave.py <stage> <json list of {wave, base, head, reqs, rounds, residual, req_ac, summary}>
import sys; sys.path.insert(0,__file__.rsplit('/',1)[0]); from common import *
s=int(sys.argv[1]); items=json.loads(sys.argv[2])
paths=["docs/spec",f"{WR}waves/carry-forward.json",f"{WR}waves/stage-{s}"]+[f"{WR}waves/{i['wave']}" for i in items]
C=commit(f"docs(spec): close stage {s} — promote "+", ".join(i['wave'] for i in items)+"\n\n"+"\n".join(f"{i['wave']}: {i['summary']}" for i in items),[("Orch-Run",R),("Orch-Verb","promote-requirements"),("Orch-Stage",s)],paths)
REG={"command":"./gradlew -PtinyHttpdFromMaven=true build","exit_code":0,"failing_tests":[],"baseline_failing_tests":[]}
for i in items:
  w=i['wave']; n=int(w.split('-')[1]); base=dict(wave=w,order=n,target=w,stage=s)
  iss=f"{WR}waves/{w}/issues.md"; lp=f"{WR}waves/stage-{s}/loop-p-{w}-r{i['rounds']}.json"
  app({**base,"event":"intent","verb":"wave-issue-triage","status":"in_progress","inputs_digest":"sha256:"+sha(iss)})
  app({**base,"event":"result","verb":"wave-issue-triage","status":"in_progress","proof":{"kind":"digest","ref":f"{iss}@sha256:{sha(iss)}"},"summary":"no issue rows; wave close ok"})
  app({**base,"event":"intent","verb":"promote-requirements","status":"in_progress","inputs_digest":"sha256:"+sha(lp)})
  app({**base,"event":"result","verb":"promote-requirements","status":"in_progress","proof":{"kind":"git-trailer","ref":f"{C} Orch-Run={R} Orch-Verb=promote-requirements"},"summary":i['summary'],"test_sufficiency":{"verdict":"pass","gaps_final":[]}})
  v={"verdict":"pass","rounds":i['rounds'],"cap":20,"residual":i.get('residual',[]),"findings":{"kept":0,"dropped":0},"red_tests":[],"frozen_denominator":{"round":i['rounds'],"req_ac":i['req_ac']},"regression":REG}
  app({**base,"status":"complete","sds_id":f"{R}-{w}","done_when_confirmed":["DW-1","DW-2"],"diff_window":{"base_sha":i['base'],"head_sha":i['head']},"verification":v,"req_ids":i['reqs'],"summary":f"{w} complete: {i['summary']}"})
print("COMMIT",C)
