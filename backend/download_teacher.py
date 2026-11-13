"""Resumable bounded range download for the public DeBERTa checkpoint."""
import hashlib
import time
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import requests

DEST=Path(__file__).resolve().parent/'artifacts/pretrained/teacher/pytorch_model.bin'
SIZE=286059269
SHA='d4b0ebcc7799a2c2dde1a39199cb0ed709f2c6b63a48b8c05133b77955f271a9'
URL='https://huggingface.co/microsoft/deberta-v3-small/resolve/a36c739020e01763fe789b4b85e2df55d6180012/pytorch_model.bin'
CHUNK=8*1024*1024


def main():
    if DEST.exists() and DEST.stat().st_size==SIZE:
        if hashlib.sha256(DEST.read_bytes()).hexdigest()==SHA:
            print('Teacher checkpoint verified',flush=True)
            return
    parts=DEST.parent/'parts'
    parts.mkdir(exist_ok=True)
    if DEST.exists():
        with DEST.open('rb') as f:
            for i in range(DEST.stat().st_size//CHUNK):
                p=parts/str(i)
                data=f.read(CHUNK)
                if not p.exists(): p.write_bytes(data)

    def fetch(i):
        start=i*CHUNK
        end=min(SIZE,start+CHUNK)-1
        p=parts/str(i)
        if p.exists() and p.stat().st_size==end-start+1: return
        for attempt in range(5):
            try:
                with requests.get(URL+'?download=true&part='+str(i),
                                  headers={'Range':f'bytes={start}-{end}'},
                                  timeout=(20,45),stream=True) as r:
                    r.raise_for_status()
                    if r.status_code!=206: raise ValueError('Server did not honor range')
                    data=b''.join(r.iter_content(256*1024))
                if len(data)!=end-start+1: raise ValueError('Incomplete range')
                p.write_bytes(data)
                print('Downloaded part',i+1,'of',(SIZE+CHUNK-1)//CHUNK,flush=True)
                return
            except Exception as e:
                print('Retry part',i,str(e)[:100],flush=True)
                if attempt==4: raise
                time.sleep(2)
    with ThreadPoolExecutor(max_workers=8) as pool:
        list(pool.map(fetch,range((SIZE+CHUNK-1)//CHUNK)))
    assembled=DEST.with_suffix('.verified')
    with assembled.open('wb') as out:
        for i in range((SIZE+CHUNK-1)//CHUNK): out.write((parts/str(i)).read_bytes())
    if hashlib.sha256(assembled.read_bytes()).hexdigest()!=SHA:
        raise RuntimeError('Checkpoint hash mismatch')
    assembled.replace(DEST)
    print('Teacher checkpoint verified',flush=True)


if __name__=='__main__': main()
