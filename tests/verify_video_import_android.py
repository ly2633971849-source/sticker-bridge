"""Exercise a controlled original through public MediaStore and the real app ACTION_SEND entry."""
import argparse,hashlib,io,json,os,re,shlex,shutil,subprocess,time
from pathlib import Path
from PIL import Image
p=argparse.ArgumentParser();p.add_argument('--device',required=True);p.add_argument('--source',type=Path,required=True);p.add_argument('--source-uri',help='Existing, publicly scanned controlled fixture; avoids shell-dependent MediaStore insert behavior');p.add_argument('--mime',default='video/mp4');p.add_argument('--name',required=True);p.add_argument('--expect-missing-motion',action='store_true');p.add_argument('--expect-static',action='store_true');p.add_argument('--adb');o=p.parse_args()
assert not(o.expect_static and o.expect_missing_motion)
if not re.fullmatch(r'[a-zA-Z0-9_-]+',o.name):p.error('--name must contain only letters, digits, _ or -')
project=Path(__file__).resolve().parents[1];out=project/'build/video-import';out.mkdir(parents=True,exist_ok=True)
sdk=os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT')
sdk_adb=Path(sdk)/'platform-tools'/('adb.exe' if os.name=='nt' else 'adb') if sdk else None
adb_path=o.adb or (str(sdk_adb) if sdk_adb and sdk_adb.is_file() else shutil.which('adb'))
if not adb_path:p.error('Android platform-tools adb is required; set ANDROID_HOME or use --adb')
adb=[adb_path,'-s',o.device]
def run(*args,input=None):
    result=subprocess.run([*adb,*args],input=input,capture_output=True,timeout=30)
    if result.returncode:raise RuntimeError(result.stderr.decode('utf-8','replace')+result.stdout.decode('utf-8','replace'))
    return result.stdout
def status():
    raw=run('shell','content','call','--uri','content://com.stickerbridge.app.control','--method','status').decode('utf-8')
    return json.JSONDecoder().raw_decode(raw.split('json=',1)[1])[0]
try:before=status()
except (IndexError,json.JSONDecodeError):raise SystemExit('Install a development build made with python build.py --debuggable on the selected test device.')
assert not before['busy']
def album_rows():return run('shell',shlex.join(['content','query','--uri','content://media/external/images/media','--projection','_id','--where',"relative_path='Pictures/StickerBridge/'"]))
before_album=album_rows() if o.expect_missing_motion else None
content=o.source.read_bytes();digest=hashlib.sha256(content).hexdigest()
suffix={'image/png':'.png','image/gif':'.gif','image/webp':'.webp','image/jpeg':'.jpg','video/mp4':'.mp4'}.get(o.mime,o.source.suffix)
if not re.fullmatch(r'\.[a-zA-Z0-9]{1,8}',suffix):p.error('Fixture must have a media file extension')
if o.source_uri:uri=o.source_uri
else:
    filename='StickerBridge-QA-'+digest[:16]+suffix;path='/sdcard/Download/'+filename
    run('push',str(o.source),path);run('shell','am','broadcast','-a','android.intent.action.MEDIA_SCANNER_SCAN_FILE','-d','file://'+path)
    query=shlex.join(['content','query','--uri','content://media/external/file','--projection','_id:_size','--where',"_display_name='"+filename+"'"])
    for _ in range(15):
        found=re.findall(r'_id=(\d+)',run('shell',query).decode('utf-8'))
        if found:break
        time.sleep(.3)
    assert len(found)==1,'Controlled scanned fixture is absent or ambiguous; no app action launched'
    uri='content://media/external/file/'+found[0]
assert uri.startswith('content://media/')
assert hashlib.sha256(run('exec-out','content','read','--uri',uri)).hexdigest()==digest
run('shell','am','start','-n','com.stickerbridge.app/.MainActivity','-a','android.intent.action.SEND','-t',o.mime,'-d',uri,'--eu','android.intent.extra.STREAM',uri,'--grant-read-uri-permission')
started=time.monotonic();s=None
for _ in range(80):
    s=status()
    if s['jobId']!=before['jobId'] and not s['busy']:break
    time.sleep(.2)
else:raise AssertionError('Bounded observation ended; leave the active app conversion intact')
if o.expect_missing_motion:
    assert not s['busy'] and not s['savedResult'] and s['progress']<100 and s['last'].get('jobId')!=s['jobId'] and '缺少动画片段' in s['detail'],s['detail']
    assert before_album==album_rows(),'Incomplete motion file still published a cover'
    report={'sourceUri':uri,'sourceSha256':digest,'missingMotionRejected':True,'publicAlbumUnchanged':True,'completedWithout100':True}
    (out/(o.name+'-validation.json')).write_text(json.dumps(report,indent=2),encoding='utf8');print(json.dumps(report));raise SystemExit(0)
assert s['savedResult'] and s['last'].get('jobId')==s['jobId'],(s['stage'],s['detail'])
r=s['last'];assert r['inputMethod']=='shared_file' and r['sourceSha256']==digest and (r['frames']==1 if o.expect_static else r['frames']>1)
public=run('exec-out','content','read','--uri',r['mediaUri']);assert hashlib.sha256(public).hexdigest()==r['outputSha256']
image=Image.open(io.BytesIO(public));assert image.format==('PNG' if o.expect_static else 'GIF') and image.n_frames==r['frames'];elapsed=0;unique=set()
for i in range(image.n_frames):image.seek(i);elapsed+=image.info.get('duration',0);unique.add(hashlib.sha256(image.convert('RGB').tobytes()).hexdigest())
assert abs(elapsed-r['sourceDuration'])<=10 and (len(unique)==1 if o.expect_static else len(unique)>1)
(out/(o.name+('.png' if o.expect_static else '.gif'))).write_bytes(public)
report={'sourceUri':uri,'sourceSha256':digest,'outputSha256':r['outputSha256'],'publicOutputUri':r['mediaUri'],'frames':image.n_frames,'durationMs':elapsed,'uniqueFrames':len(unique),'conversionMs':r['conversionMs'],'decodeMs':r['decodeMs'],'encodeMs':r['encodeMs'],'reusedConversion':r['reusedConversion'],'observedWallSeconds':time.monotonic()-started}
(out/(o.name+'-validation.json')).write_text(json.dumps(report,indent=2),encoding='utf-8');print(json.dumps(report))
