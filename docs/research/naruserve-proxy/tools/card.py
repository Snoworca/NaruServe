# usage: card.py <closed-stage> <close-commit> <next-stage> <next-waves csv>
import sys; sys.path.insert(0,__file__.rsplit('/',1)[0]); from common import *
s,c,ns,nw=int(sys.argv[1]),sys.argv[2],int(sys.argv[3]),sys.argv[4].split(',')
P=f"kiwi/orchestrator/{R}/resume-card.json"; d=json.load(open(P))
for k in ['invariant_digest','written_at']: d.pop(k,None)
d['done'].append({"key":f"stage-{s}-closed","proof":{"kind":"git-trailer","ref":f"{c} Orch-Run={R} Orch-Verb=promote-requirements"}})
d['open']=[]; d['frozen']['lane_lock']={}
d['position']={"wave":int(nw[0].split('-')[1]),"stage":ns,"phase":"readiness"}
d['next_action']={"verb":"derive-readiness","args":{"stage":ns,"waves":nw},"preconditions":["P-DESIGN-FROZEN","P-WAVE-ISSUES-CLOSED"]}
d['blocked_on']=None
print(sh("speckiwi","orchestrate","card","write","--run-id",R,"--payload",json.dumps(d),"--json",check=False)[:300])
