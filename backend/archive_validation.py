"""Archive release checks and the small trained models suitable for Git."""
import json
import shutil
import subprocess
from datetime import datetime
from zoneinfo import ZoneInfo

from common import ART, ROOT, digest, write_json
from download_models import MODELS, REVISIONS


def main():
    stamp=datetime.now(ZoneInfo('Asia/Kolkata')).strftime('%Y%m%d-%H%M%S')
    run=ROOT/'backend/runs/validation'/(stamp+'-mac')
    run.mkdir(parents=True,exist_ok=True)
    files=['stage0_parity','stream_parity','onnx_parity','full_stream_parity','wasm_parity',
           'scorer_tests','pipeline_tests','evaluation']
    for name in files:
        source=ART/(name+'.log')
        if not source.exists(): raise FileNotFoundError(source)
        shutil.copyfile(source,run/(name+'.txt'))
    source_hashes={str(p.relative_to(ROOT)):digest(p)
                  for pattern in ('backend/*.py','frontend/js/*.js') for p in ROOT.glob(pattern)}
    write_json(run/'source_hashes.json',source_hashes)
    write_json(run/'training_histories.json',{
        str(p.relative_to(ART)):json.loads(p.read_text())
        for parent in ('teacher','student','student_l6')
        for p in (ART/parent).glob('*/history.json')})
    write_json(run/'pretrained_models.json',{
        name:{'repo':repo,'revision':REVISIONS[name]} for name,repo in MODELS.items()})
    shutil.copyfile(ROOT/'backend/requirements-mac-lock.txt',run/'requirements.txt')
    for parent in ('teacher','student','student_l6'):
        for path in (ART/parent).glob('*/tokenizer_config.json'):
            config=json.loads(path.read_text())
            config['truncation_side']='left'
            write_json(path,config)
    models=ROOT/'backend/models'
    models.mkdir(exist_ok=True)
    for name in ('C_embeddings_lr.joblib','D_embeddings_library.joblib','stream_model_teacher.json',
                 'stage0_available_model.json','stage1_latency.json'):
        shutil.copyfile(ART/name,models/name)
    write_json(models/'manifest.json',{p.name:{'sha256':digest(p),'bytes':p.stat().st_size}
                                     for p in models.iterdir() if p.name!='manifest.json'})
    write_json(run/'release.json',json.loads((ROOT/'frontend/model/release.json').read_text()))
    print('Archived validation:',run)


if __name__=='__main__': main()
