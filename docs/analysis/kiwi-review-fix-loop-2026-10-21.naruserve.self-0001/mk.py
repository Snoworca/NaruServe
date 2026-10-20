import re,json,subprocess,sys
R='/Users/beom/work/git/NaruServe'
BASE='3a2717bbadfde9e626b3568bb2ee9455ad1030ac'
cands=[l.strip() for l in open(R+'/docs/analysis/kiwi-review-fix-loop-2026-10-21.naruserve.self-0001/candidates.txt') if l.strip()]
prose=[c for c in cands if c in('CLAUDE.md','README.md','THIRD-PARTY-LICENSES.md')]
inc=[c for c in cands if c not in prose]
def part(f):
    if f.startswith('NaruServe-tinyhttpd/'): return 'P6'
    if f.startswith('NaruServe-servlet/'): return 'P7'
    if re.match(r'NaruServe(-gson|-kotlin|-slf4j|-logcat|-filelog)?/',f): return 'P8'
    if f.startswith('NaruServe-api/'):
        t='/src/test/' in f
        b=f.split('/')[-1]
        if t:
            return 'P10' if b[0].upper()<'I' or f.split('/')[-2]!='naruserve' and False else 'P11'
        m=re.match(r'NaruServe-api/src/main/java/com/snoworca/naruserve/(.*)',f)
        if not m: return 'P1'
        rest=m.group(1)
        if '/' not in rest: return 'P1'
        n=rest.split('/')[-1]
        if re.match(r'(WebSocket|Sse|DefaultSse|DefaultWebSocket|OriginCheck|DefaultWebSocketSession)',n): return 'P4'
        if re.match(r'(StaticHandler|FileResponder|FileResourceResolver|DirectoryListing|StaticMount|StaticPath|DefaultStaticOptions|DefaultMimeTypes|AccessLog|DefaultAccessLog|SafeLogger|CachePattern|PercentDecoder|ErrorPath|StandardErrors)',n): return 'P5'
        if re.match(r'(DefaultRequest|DefaultResponse|ResponseState|BodyState|BodyTooLarge|Cookie|JsonBody|FormBody|FormData|Multipart|Spool|SpoolRegistry|SpoolingBuffer|DefaultUploadFile|LimitedInput|ProxyTrust|RequestIds)',n): return 'P3'
        return 'P2'
    return 'P9'
parts={}
for f in inc: parts.setdefault(part(f),[]).append(f)
out={}
for p,fs in parts.items():
    L=[]
    for f in fs:
        n=int(subprocess.run("git -C %s diff -U0 %s HEAD -- '%s' | grep -c '^@@'"%(R,BASE,f),shell=True,capture_output=True,text=True).stdout.strip() or 0)
        L.append({'file':f,'hunks_total':n})
    out[p]=L
    json.dump(L,open('denom_%s.json'%p,'w'),indent=0)
json.dump({'excluded_prose':prose},open('excluded_prose.json','w'))
print({p:(len(v),sum(x['hunks_total'] for x in v)) for p,v in sorted(out.items())}, len(inc),len(prose),len(cands))
