"""Download phone ASR assets into ignored storage; pin revision and content hashes."""
import os
os.environ.setdefault('HF_HUB_DISABLE_XET','1')
from pathlib import Path
import urllib.request
from huggingface_hub import hf_hub_download
from common import ART, ROOT, digest, write_json

REPO='csukuangfj/sherpa-onnx-whisper-tiny.en'
REVISION='d026532c022fa99fd789d6b32446a1df7b6bfc43'
VAD_URL='https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx'


def main():
    dest=ART/'v2/asr'; dest.mkdir(parents=True,exist_ok=True)
    for name in ('tiny.en-encoder.int8.onnx','tiny.en-decoder.int8.onnx','tiny.en-tokens.txt'):
        print('Downloading',name,flush=True)
        hf_hub_download(REPO,name,revision=REVISION,local_dir=dest)
    vad=dest/'silero_vad.onnx'
    if not vad.exists():
        tmp=vad.with_suffix('.part')
        urllib.request.urlretrieve(VAD_URL,tmp)
        tmp.replace(vad)
    manifest={'sherpa_onnx':'2.0.1','repo':REPO,'revision':REVISION,'vad_url':VAD_URL,
              'phone_binary_hash_comparison':'pending: Android asset hashes not supplied',
              'files':{p.name:{'bytes':p.stat().st_size,'sha256':digest(p)} for p in sorted(dest.iterdir()) if p.is_file()}}
    write_json(ROOT/'data-eval/v2/asr_assets.json',manifest)
    print('Saved asset hashes',flush=True)


if __name__=='__main__': main()
