"""Download only the pretrained files needed by the experiment, inside artifacts."""
import os
from pathlib import Path

os.environ['HF_HUB_DISABLE_XET'] = '1'
from huggingface_hub import snapshot_download

ROOT = Path(__file__).resolve().parent / 'artifacts/pretrained'
MODELS = {
    'minilm': 'sentence-transformers/all-MiniLM-L6-v2',
    'teacher': 'microsoft/deberta-v3-small',
    'student': 'sentence-transformers/paraphrase-MiniLM-L3-v2',
}
REVISIONS = {
    'minilm': '1110a243fdf4706b3f48f1d95db1a4f5529b4d41',
    'teacher': 'a36c739020e01763fe789b4b85e2df55d6180012',
    'student': '4ca70771034acceecb2e72475f72050fcdde4ddc',
}

if __name__ == '__main__':
    os.environ['HF_HUB_DISABLE_XET'] = '1'
    for name, repo in MODELS.items():
        path = ROOT / name
        print('Downloading', repo, flush=True)
        snapshot_download(repo, revision=REVISIONS[name], local_dir=path, max_workers=2,
                          allow_patterns=['*.json', 'model.safetensors', 'pytorch_model.bin',
                                          'vocab.txt', 'spm.model', 'sentencepiece.bpe.model',
                                          '1_Pooling/config.json', 'modules.json'])
        print('Ready:', path, flush=True)
