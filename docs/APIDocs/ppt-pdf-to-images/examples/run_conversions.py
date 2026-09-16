#!/usr/bin/env python3
"""Call a running local Functions host; retain original response bytes and evidence."""
import argparse
import hashlib
import json
import platform
import subprocess
import time
import urllib.parse
import urllib.request
import zipfile
from datetime import datetime, timezone
from pathlib import Path
from PIL import Image

ROOT = Path(__file__).resolve().parent
REPO = ROOT.parents[3]

def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

def main():
    p=argparse.ArgumentParser();p.add_argument('--base-url',default='http://localhost:7071');args=p.parse_args()
    if urllib.parse.urlsplit(args.base_url).hostname not in ('localhost','127.0.0.1'):
        p.error('This evidence script is for a local host only.')
    report={'recordedAt':datetime.now(timezone.utc).isoformat(),'environment':{'os':platform.system(),'architecture':platform.machine(),'python':platform.python_version(),'java':subprocess.run(['java','-version'],capture_output=True,text=True).stderr.splitlines()[0],'functionsCoreTools':subprocess.check_output(['func','--version'],text=True).strip(),'sourceCommit':subprocess.check_output(['git','rev-parse','HEAD'],cwd=REPO,text=True).strip()},'measurement':'One sequential request per case on an already running local macOS host. Includes request upload and response download. Not an Azure benchmark; not an isolated cold-start measurement.','cases':[]}
    jar=REPO/'functions/ppt-pdf-to-images/target/azure-functions/slide2image-local/slide2image-1.0.0-SNAPSHOT.jar'
    report['environment']['functionJarSha256']=sha(jar)
    for filename in ('quarterly-review.pptx','operations-report.pdf'):
        source=ROOT/'inputs'/filename
        stem=source.stem
        options={'filename':filename,'format':'png'}
        url=args.base_url+'/api/convert?'+urllib.parse.urlencode(options)
        request=urllib.request.Request(url,data=source.read_bytes(),headers={'Content-Type':'application/octet-stream'},method='POST')
        start=time.perf_counter()
        with urllib.request.urlopen(request,timeout=180) as response:
            body=response.read();elapsed=time.perf_counter()-start;status=response.status
            headers={key:response.headers[key] for key in ['Content-Type','Content-Disposition','X-Page-Count']}
        target=ROOT/'outputs'/f'{stem}.zip';target.write_bytes(body)
        pages=[]
        with zipfile.ZipFile(target) as archive:
            for entry in archive.infolist():
                assert Path(entry.filename).name==entry.filename and entry.filename.endswith('.png')
                image_path=ROOT/'outputs'/stem/entry.filename;image_path.parent.mkdir(exist_ok=True);image_path.write_bytes(archive.read(entry))
                with Image.open(image_path) as image:
                    pages.append({'path':'outputs/'+stem+'/'+entry.filename,'sizeBytes':image_path.stat().st_size,'sha256':sha(image_path),'width':image.width,'height':image.height})
        report['cases'].append({'id':stem,'input':{'path':'inputs/'+filename,'sizeBytes':source.stat().st_size,'sha256':sha(source)},'request':{'method':'POST','path':'/api/convert','options':options,'contentType':'application/octet-stream'},'response':{'status':status,'headers':headers,'elapsedSeconds':round(elapsed,3),'path':'outputs/'+target.name,'sizeBytes':len(body),'sha256':sha(target)},'pages':pages})
        print(filename,status,round(elapsed,3),len(body),[(x['width'],x['height']) for x in pages])
    source=ROOT/'inputs'/'operations-report.pdf'
    options={'filename':source.name,'page':'3','format':'jpeg','width':'1200'}
    request=urllib.request.Request(args.base_url+'/api/convert?'+urllib.parse.urlencode(options),data=source.read_bytes(),headers={'Content-Type':'application/octet-stream'},method='POST')
    start=time.perf_counter()
    with urllib.request.urlopen(request,timeout=180) as response:
        body=response.read();elapsed=time.perf_counter()-start;status=response.status
        headers={key:response.headers[key] for key in ['Content-Type','Content-Disposition','X-Page-Count']}
    target=ROOT/'outputs'/'operations-report-page-0003.jpeg';target.write_bytes(body)
    with Image.open(target) as image:dimensions={'width':image.width,'height':image.height}
    report['cases'].append({'id':'pdf-single-jpeg','input':{'path':'inputs/'+source.name,'sizeBytes':source.stat().st_size,'sha256':sha(source)},'request':{'method':'POST','path':'/api/convert','options':options,'contentType':'application/octet-stream'},'response':{'status':status,'headers':headers,'elapsedSeconds':round(elapsed,3),'path':'outputs/'+target.name,'sizeBytes':len(body),'sha256':sha(target),**dimensions}})
    (ROOT/'run.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
    print('PDF JPEG:',status,round(elapsed,3),dimensions)

if __name__=='__main__':main()
