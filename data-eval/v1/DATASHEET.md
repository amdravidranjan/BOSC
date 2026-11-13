# Available-data experiment

BothBosu/scam-dialogue (Apache-2.0 per repository documentation) and BOSC templates. Hugging Face train is stratified 70/15/15 into train/calib/dev; its test remains held out. Template train is divided into disjoint train/calib calls; template dev/test remain held out. Exact normalized caller-transcript duplicates are removed, preserving test first. Common template phrases remain: this is not a semantic or 12-word-ngram-disjoint benchmark. No lockbox is read. No human, red-team, ASR, or real-audio results are claimed. Unlike the full README plan, there is no new LLM-generated corpus. HF tactic loss is masked. Gold template tactics are used as provided, without manual review.

See manifest.json for counts and SHA-256 hashes. Raw datasets are local and git-ignored.
