# V2 data preparation — NOT a completed dataset

Actual generated/cleaned/reviewed v2 calls: **0**. No v2 training has run.
`generation_plan_summary.json` describes planned counts, not observed data.
The plan has 6,000 train, 1,200 calib, 600 dev and 600 test calls, half scam,
15% Hinglish, at least 20% polite scams and 40% hard genuine negatives.
S1/S10 sampling weights are 1.5 times other scam types. Scenario seeds are
disjoint; train/calib use prompt v1, dev/test use reworded prompt v2.

## Sources and licences

- LLM provider/model, output-training terms and spending limit: **pending user input**.
- BothBosu/scam-dialogue and repository templates are existing v1 sources;
  neither has been imported into a completed v2 split. Templates must remain
  at most 30% of the final training originals.
- ASR model provenance and file hashes are in `asr_assets.json`. These are the
  current pinned files from the requested Hugging Face repository. Exact equality
  with Android assets is pending the phone asset hashes, including Silero.
- No real names/numbers or raw generated transcripts are committed. Raw calls,
  audio and review queues are ignored by Git.

## Prepared processing

`v2_data.py` checks schema, caller-turn tactics and suspicious boundaries,
deduplicates exact transcripts, >0.90 MiniLM cosine similarity, and cross-split
12-word n-grams. Whole-call embeddings pool token chunks rather than truncating
long calls. Drops are logged; lost strata must be replenished before training.
The review queue samples at least 5%, or 10% for hard negatives. Automated checks
do not substitute for human review. No review has been performed on v2 data.

`v2_asr.py` implements Indian Edge TTS, 16 kHz PCM path A and an 8 kHz A-law/
300–3400 Hz path B, 20/10 dB noise, ±6 dB volume, the specified Silero settings,
and independent Sherpa 2.0.1 tiny.en segment decoding. A one-call v1 smoke test
produced five nonempty segments on each path. This is not corpus augmentation.
Semantic segment labels remain pending review; time alignment is not enough to
infer which tactic survived garbled or split speech. Text substitutions are
marked text noise, never measured ASR.

## Remaining work

Choose/configure a permitted LLM provider and budget; generate and replenish
the corpus; complete review, ASR copies and segment annotation; build immutable
splits with final counts and hashes; retrain Stage 0 first, then remaining models;
evaluate clean and both ASR paths. No multilingual or real-world performance is
established. Lockbox data has not been opened.
