"""Resume the Mac available-data run, log each step, and gate browser activation."""
import hashlib
import json
import subprocess
import sys
import time

from common import ART, ROOT, digest, write_json


def main():
    ART.mkdir(exist_ok=True)
    # Another process may still be transferring this checkpoint in bounded ranges.
    checkpoint=ART/'pretrained/teacher/pytorch_model.bin'
    expected='d4b0ebcc7799a2c2dde1a39199cb0ed709f2c6b63a48b8c05133b77955f271a9'
    while not (checkpoint.exists() and checkpoint.stat().st_size==286059269 and digest(checkpoint)==expected):
        print('Waiting for verified DeBERTa checkpoint',flush=True)
        time.sleep(10)
    def run(name,command):
        print('START',name,flush=True)
        with (ART/(name+'.log')).open('w') as log:
            proc=subprocess.run(command,cwd=ROOT,stdout=log,stderr=subprocess.STDOUT)
        if proc.returncode:
            print((ART/(name+'.log')).read_text()[-5000:],flush=True)
            raise RuntimeError(name+' failed; see backend/artifacts/'+name+'.log')
        print('DONE',name,flush=True)
    py=sys.executable
    download_log=(ART/'downloads.log').open('w')
    download=subprocess.Popen([py,'-u','backend/download_models.py'],cwd=ROOT,
                              stdout=download_log,stderr=subprocess.STDOUT)
    run('stage2',[py,'-u','backend/stage2_teacher.py','--device','mps'])
    run('stage3_teacher',[py,'-u','backend/stage3_stream.py'])
    if download.wait()!=0:
        raise RuntimeError('Pretrained student download failed; see artifacts/downloads.log')
    download_log.close()
    run('stage4_distill',[py,'-u','backend/stage4_distill.py','--device','mps'])
    initial_student=json.loads((ART/'stage4_distill_metrics.json').read_text())
    if not initial_student['student_f1_gate_passed']:
        run('stage4_distill_l6',[py,'-u','backend/stage4_distill.py','--device','mps',
                                '--model','minilm','--length','192'])
    run('stage4_export',[py,'-u','backend/stage4_export.py'])
    run('stage3_student',[py,'-u','backend/stage3_stream.py','--preds','student_int8','--name','student'])
    for name,file in [('stage0_parity','parity_test.mjs'),('stream_parity','parity_stream.mjs'),
                      ('onnx_parity','parity_onnx.mjs'),('full_stream_parity','parity_full_stream.mjs'),
                      ('wasm_parity','parity_wasm.mjs'),
                      ('scorer_tests','test_neural_scorer.mjs')]:
        run(name,['node','backend/'+file])
    run('pipeline_tests',[py,'backend/test_pipeline.py'])
    run('evaluation',[py,'backend/evaluate_all.py'])
    metrics=json.loads((ART/'stage4_distill_metrics.json').read_text())
    enabled=metrics['student_f1_gate_passed']
    write_json(ROOT/'frontend/model/release.json',{'enabled':enabled,
               'scope':'Synthetic available-data experiment',
               'onnx_sha256':digest(ROOT/'frontend/model/student/onnx/model_quantized.onnx'),
               'stream_sha256':digest(ROOT/'frontend/model/stream_model.json'),
               'reason':'All parity checks passed; '+('student quality gate passed' if enabled else 'student quality gate failed; Stage 0 remains active')})
    with (ROOT/'backend/requirements-mac-lock.txt').open('w') as out:
        subprocess.run([py,'-m','pip','freeze'],stdout=out,check=True)
    print('PIPELINE FINISHED; neural release enabled:',enabled,flush=True)


if __name__=='__main__': main()
