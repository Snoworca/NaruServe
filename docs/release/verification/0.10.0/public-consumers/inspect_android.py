from pathlib import Path
import hashlib,json,re,subprocess,sys,zipfile,xml.etree.ElementTree as ET
base=Path(__file__).resolve().parent
project=base/'android'
apk=Path(sys.argv[1]) if len(sys.argv)>1 else project/'app/build/outputs/apk/release/app-release-unsigned.apk'
evidence=project/'app/build/evidence';evidence.mkdir(parents=True,exist_ok=True)
sdk=Path('/opt/homebrew/share/android-commandlinetools')
manifest=subprocess.check_output([str(sdk/'cmdline-tools/latest/bin/apkanalyzer'),'manifest','print',str(apk)],text=True)
(evidence/'manifest.xml').write_text(manifest)
root=ET.fromstring(manifest);ns='{http://schemas.android.com/apk/res/android}'
assert any(p.get(ns+'name')=='android.permission.INTERNET' for p in root.findall('uses-permission'))
assert root.find('uses-sdk').get(ns+'minSdkVersion')=='21'
assert root.find('uses-sdk').get(ns+'targetSdkVersion')=='36'
assert 'android.permission.INTERNET' not in (project/'app/src/main/AndroidManifest.xml').read_text()
assert (project/'app/app-rules.pro').read_text().strip()=='-keep class consumer.android.ServerEntry { *; }'
configuration=(project/'app/build/outputs/mapping/release/configuration.txt').read_text()
assert 'com.snoworca.naruserve.bootstrap.DefaultAppFactory' in configuration
assert 'com.snoworca.naruserve.logcat.LogcatLogger' in configuration
mapping=(project/'app/build/outputs/mapping/release/mapping.txt').read_text()
dumps=[];notices={}
with zipfile.ZipFile(apk) as z:
    service='META-INF/services/com.snoworca.naruserve.spi.AppFactory'
    providers=z.read(service).decode().strip();assert providers=='com.snoworca.naruserve.bootstrap.DefaultAppFactory'
    (evidence/'service-provider.txt').write_text(providers+'\n')
    for name in ['META-INF/LICENSE-tinyhttpd.txt','META-INF/LICENSE-nanohttpd.txt','META-INF/NOTICE-tinyhttpd.txt']:
        notices[name]=hashlib.sha256(z.read(name)).hexdigest()
    for name in z.namelist():
        if re.fullmatch(r'classes\d*\.dex',name):
            target=evidence/name;target.write_bytes(z.read(name))
            text=subprocess.check_output([str(sdk/'build-tools/36.0.0/dexdump'),str(target)],text=True)
            (evidence/(name+'.txt')).write_text(text);dumps.append(text)
dex='\n'.join(dumps)
retained={}
for name in ['com.snoworca.naruserve.bootstrap.DefaultAppFactory','com.snoworca.naruserve.logcat.LogcatLogger','com.snoworca.naruserve.gson.GsonJsonCodec','com.google.gson.Gson']:
    match=re.search(r'^'+re.escape(name)+r' -> ([^:]+):$',mapping,re.M);assert match,name+' missing from R8 mapping'
    descriptor='L'+match.group(1).replace('.','/')+';';assert descriptor in dex,descriptor+' missing from APK DEX'
    retained[name]=match.group(1)
factory_blocks=[b for b in re.split(r'(?m)^Class #\d+',dex) if "Class descriptor  : 'Lcom/snoworca/naruserve/bootstrap/DefaultAppFactory;'" in b]
assert len(factory_blocks)==1
assert "'create'" in factory_blocks[0] and "'<init>'" in factory_blocks[0]
result={'apk':str(apk),'sha256':hashlib.sha256(apk.read_bytes()).hexdigest(),'minSdk':21,'targetSdk':36,'internetPermissionInherited':True,'serviceProvider':providers,'R8RetainedClasses':retained,'factoryConstructorAndCreateRetained':True,'appKeepRules':'own ServerEntry only','tinyNoticesSha256':notices,'deviceOrEmulatorRun':False,'signed':False}
(evidence/'android-proof.json').write_text(json.dumps(result,indent=2));print(json.dumps(result,indent=2))
