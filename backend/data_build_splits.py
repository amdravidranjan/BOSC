"""Build reproducible available-data splits. Never reads the human lockbox."""
import json
import random
import re
from collections import Counter

from sklearn.model_selection import train_test_split

from common import DATA, ROOT, caller_utts, digest, load_hf, load_jsonl, write_json


def signature(c):
    return re.sub(r'\W+', ' ', ' '.join(caller_utts(c)).lower()).strip()


def main():
    hf = load_hf(ROOT/'data-eval/raw/scam-dialogue_train.csv', 'train')
    train, rest = train_test_split(hf, test_size=.3, random_state=13,
                                  stratify=[c['type'] for c in hf])
    calib, dev = train_test_split(rest, test_size=.5, random_state=13,
                                 stratify=[c['type'] for c in rest])
    gen = load_jsonl(ROOT/'data-eval/generated/train.jsonl')
    random.Random(13).shuffle(gen)
    gen_calib, gen_train = gen[:600], gen[600:]
    # Target <=30% templates before exact deduplication. The manifest records
    # the final share (it can shift slightly when HF duplicates are removed).
    gen_train = gen_train[:int(len(train)*.3/.7)]
    splits = {'train': train+gen_train, 'calib': calib+gen_calib,
              'dev': dev+load_jsonl(ROOT/'data-eval/generated/dev.jsonl'),
              'test_synth': load_hf(ROOT/'data-eval/raw/scam-dialogue_test.csv','test')+
              load_jsonl(ROOT/'data-eval/generated/test.jsonl')}
    seen, dropped, counts = set(), {}, {}
    DATA.mkdir(exist_ok=True)
    # Preserve tests; discard duplicate caller transcripts from fitting splits.
    for split in ('test_synth', 'dev', 'calib', 'train'):
        kept = []
        for c in splits[split]:
            sig = signature(c)
            if not sig or sig in seen:
                continue
            seen.add(sig)
            c['split'] = split
            kept.append(c)
        dropped[split] = len(splits[split])-len(kept)
        (DATA/(split+'.jsonl')).write_text(''.join(json.dumps(c)+'\n' for c in kept))
        counts[split] = {'calls':len(kept), 'sources':dict(Counter(c['source'] for c in kept)),
                         'types':dict(Counter(c['type'] for c in kept))}
    write_json(DATA/'manifest.json', {'seed':13, 'counts':counts, 'duplicates_dropped':dropped,
                                    'hashes':{s:digest(DATA/(s+'.jsonl')) for s in splits}})
    (DATA/'DATASHEET.md').write_text(
        '# Available-data experiment\n\n'
        'BothBosu/scam-dialogue (Apache-2.0 per repository documentation) and BOSC templates. '
        'Hugging Face train is stratified 70/15/15 into train/calib/dev; its test remains held out. '
        'Template train is divided into disjoint train/calib calls; template dev/test remain held out. '
        'Exact normalized caller-transcript duplicates are removed, preserving test first. '
        'Common template phrases remain: this is not a semantic or 12-word-ngram-disjoint benchmark. '
        'No lockbox is read. No human, red-team, ASR, or real-audio results are claimed. '
        'Unlike the full README plan, there is no new LLM-generated corpus. '
        'HF tactic loss is masked. Gold template tactics are used as provided, without manual review.\n\n'
        'See manifest.json for counts and SHA-256 hashes. Raw datasets are local and git-ignored.\n')
    print(json.dumps(counts, indent=2))


if __name__ == '__main__':
    main()
