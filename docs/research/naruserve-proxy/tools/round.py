# usage: round.py <file> <wave-n> <round> <fixApplied 0/1> [carry json {"LP-x":["wave-k","OWNER","note"]}] [streakBefore]
import sys; sys.path.insert(0,__file__.rsplit('/',1)[0]); from common import *
F=sys.argv[1]; wn=sys.argv[2]; rd=int(sys.argv[3]); fix=sys.argv[4]=="1"; carry=json.loads(sys.argv[5]) if len(sys.argv)>5 else {}; sb=int(sys.argv[6]) if len(sys.argv)>6 else 0
d=json.load(open(F)); rows=[];res=[]
for r in d['rows']:
  k=bool(r.get('kept')) and r['id'] not in carry
  row={"id":r['id'],"verdict":"fail" if k else "pass","kept":k,"verifier":d['verifier']}
  if k: row["evidence"]=(r.get('evidence') or '')[:500]
  rows.append(row)
  if r['id'] in carry: res.append({"id":r['id'],"reasonClass":"cross-wave-carry-forward"})
p={"loop":"P","scope":f"wave-{wn}-post","roundIndex":rd,"mode":"normal","cap":20,"streakBefore":sb,"frozenDenominator":len(rows),"verifiers":[d['verifier']],"rows":rows,"fixAppliedThisRound":fix,"regression":{"failingTests":[],"baselineFailingTests":[],"exitCode":0},"residual":res}
print(sh("speckiwi","orchestrate","round","record","--run-id",R,"--payload",json.dumps(p),"--proof",json.dumps({"kind":"digest","ref":f"{F}@sha256:{sha(F)}"}),"--json",check=False)[:400])
if carry:
  cf=f"{WR}waves/carry-forward.json"; c=json.load(open(cf))
  for r0 in d['rows']:
    if r0['id'] in carry:
      w,own,note=carry[r0['id']]
      c['items'].append({"from":f"wave-{wn}","round":rd,"finding":r0['id'],"cites":r0.get('cites'),"test":r0.get('test'),"evidence":r0.get('evidence'),"carried_into":w,"owner_requirement":own,"reason_class":"cross-wave-carry-forward","note":note})
  json.dump(c,open(cf,'w'),indent=2,ensure_ascii=False)
