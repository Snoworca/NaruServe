import json,re,subprocess,sys
R='/Users/beom/work/git/NaruServe'
BASE='3a2717bbadfde9e626b3568bb2ee9455ad1030ac'
p=sys.argv[1]; src=sys.argv[2]
den=json.load(open('denom_%s.json'%p))
rev=json.load(open(src))
rows={r['file']:r for r in rev['coverage_rows']}
bad=[]
for d in den:
    f=d['file']; r=rows.get(f)
    if not r: bad.append((f,'missing row')); continue
    diff=subprocess.run(['git','-C',R,'diff','-U0',BASE,'HEAD','--',f],capture_output=True,text=True).stdout
    lines=[l[1:].strip() for l in diff.splitlines() if l[:1] in '+-' and not l.startswith('+++') and not l.startswith('---')]
    an=r['anchors']
    if len(an)!=d['hunks_total']: bad.append((f,'count %d != %d'%(len(an),d['hunks_total'])))
    if len(set(an))!=len(an): bad.append((f,'dup anchor'))
    for a in an:
        s=a.strip()
        s2=s[1:].strip() if s[:1] in '+-' and s[1:2]==' ' else s
        ok = s in lines or s2 in lines
        if not ok and re.fullmatch(r'[A-Za-z_]\w*',s):
            ok=any(re.search(r'\b'+re.escape(s)+r'\b',l) for l in lines)
        if not ok: bad.append((f,'anchor not found: '+a[:80]))
print(p,'files',len(den),'rows',len(rows),'bad',len(bad))
for b in bad[:40]: print(b)
