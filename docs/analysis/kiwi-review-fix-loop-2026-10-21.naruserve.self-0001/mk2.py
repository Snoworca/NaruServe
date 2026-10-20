import json,subprocess
R='/Users/beom/work/git/NaruServe'
def sh(c): return subprocess.run(c,shell=True,capture_output=True,text=True,cwd=R).stdout
mod=[l for l in sh("git diff --name-only").split('\n') if l and not l.endswith('.md')]
new=[l for l in sh("git ls-files --others --exclude-standard -- NaruServe-api NaruServe-servlet NaruServe-filelog src").split('\n') if l]
D=[]
for f in mod: D.append({'file':f,'hunks_total':int(sh("git diff -U0 -- '%s' | grep -c '^@@'"%f).strip() or 0)})
for f in new: D.append({'file':f,'hunks_total':1})
json.dump(D,open('denom_R1.json','w'))
print(len(D),sum(d['hunks_total'] for d in D))
