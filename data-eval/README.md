# Data & Evaluation: complete guide

Goal: make BOSC's accuracy **believable**. This team owns the test sets that produce the headline numbers (human-written lockbox, red team, real audio), reviews the training data, keeps the demo scripts realistic, turns results into slides, and runs the pitch.

Contents
1. Your outputs at a glance
2. Folder layout
3. Call format (the contract with Backend)
4. Labelling guide: types, tactics, first suspicious turn
5. Lockbox: writing human test calls
6. Red-team set
7. Real-audio set: recording protocol
8. Reviewing Backend's LLM-generated data
9. Demo scripts
10. Evaluation: what Backend computes, what you analyse
11. Slides and the 3-minute pitch
12. Q&A preparation
13. Rules that protect our credibility
14. Checklist, timeline and done criteria

---

## 1. Your outputs at a glance

| Output | Where | Size | Used for |
|---|---|---|---|
| Lockbox (human-written calls) | `lockbox/lockbox_v1.jsonl` | 160 scam + 160 genuine | **Headline numbers** |
| Red-team calls | `redteam/redteam_v1.jsonl` | 150 scam + 100 genuine | Robustness |
| Real-audio transcripts | `realaudio/realaudio_v1.jsonl` | 40 scam + 40 genuine | Real-audio numbers |
| LLM data review | `review/llm_review.csv` | 5% sample, 10% of hard negatives | Data quality |
| Demo scripts | `frontend/demo/scripts.json` | 6+ scripts | Demo and teleprompter |
| Error analysis | `results/errors.md` | Every lockbox miss / false alarm | Honesty slide, fixes |
| Slides + pitch | `slides/` (or a link in `slides/README.md`) | ~10 slides, 3 min | The event |
| Logs | `results/lockbox_log.md`, `results/frontend_tests.md`, `results/latency.md` | — | Evidence |

---

## 2. Folder layout

| Path | What | In Git? |
|---|---|---|
| `raw/` | BothBosu/scam-dialogue CSVs (download command in `backend/README.md` §1) | No |
| `generated/` | Template calls from `backend/gen_calls.py` | No (regenerated) |
| `v1/` | Backend's final train/calib/dev/test data + `DATASHEET.md` + `hashes.txt` | Only if < 50 MB, otherwise Drive + hash |
| `lockbox/` | **Human-written test calls. Never trained on, never used for tuning.** | Yes |
| `redteam/` | Calls designed to break the model | Yes |
| `realaudio/` | App transcripts + clean scripts of recorded role-plays (**no audio files**) | Yes |
| `review/` | Your review of LLM data | Yes |
| `tools/` | Small helper scripts (validator, log converter) | Yes |
| `audio_test/` | Two TTS test recordings for the app's replay mode | Yes |
| `results/` | Reports, metrics, figures, logs, screenshots | Yes |
| `slides/` | Deck or a link to it | Yes |

---

## 3. Call format (the contract with Backend)

One call per line, JSONL (UTF-8):
```json
{"id":"lb-anu-007","source":"lockbox","author":"anu","label":1,"type":"S2_digital_arrest",
 "first_suspicious_caller_turn":1,
 "turns":[
  {"speaker":"caller","text":"Hello, am I speaking with Mrs. Lakshmi? This is Inspector Rathore from Cyber Crime.","tactics":["authority_claim"]},
  {"speaker":"receiver","text":"Yes, what happened?","tactics":[]},
  {"speaker":"caller","text":"A parcel with your Aadhaar was caught at the airport, drugs were inside, an FIR is registered.","tactics":["threat"]},
  {"speaker":"receiver","text":"What? I didn't send anything!","tactics":[]}
 ]}
```
**Field rules**
- `id`: unique, with the set prefix: `lb-` lockbox, `rt-` red team, `ra-` real audio. Add the author's short name and a number.
- `source`: `lockbox` | `redteam` | `realaudio`.
- `author`: who wrote it (for the "writer didn't see the prompts" check). Not shown anywhere public.
- `label`: `1` scam, `0` genuine.
- `type`: one ID from §4.1 (`S1_bank_kyc` … `G9_support_genuine`; the short form `S1` / `G1` is fine).
- `first_suspicious_caller_turn`: index **counting caller turns only, from 0**. `null` for genuine calls. See §4.3.
- `turns`: alternate caller and receiver, caller first. Caller tactics are optional but strongly preferred (they enable tactic metrics). Receiver tactics are always `[]`.
- **No real people:** no real phone numbers, account numbers, or names of real individuals.

**Validate before committing:** `python data-eval/tools/validate_calls.py data-eval/lockbox/lockbox_v1.jsonl`. Write this small script first (≈ 40 lines). It should check:
- the required fields and types;
- alternating speakers;
- label/type consistency;
- `first_suspicious_caller_turn` in range (null for genuine);
- tactic names from the list;
- unique IDs;
- no 10+ digit numbers (catches real phone/account numbers).

---

## 4. Labelling guide

### 4.1 Call types
**Scam:**
- S1 fake bank/KYC
- S2 digital arrest (fake police/CBI/customs/court)
- S3 remote-access refund (AnyDesk/TeamViewer)
- S4 utility disconnection
- S5 SIM/Aadhaar block
- S6 card/loan offer with upfront fee or OTP
- S7 task/investment fraud
- S8 prize/lottery fee
- S9 fake tech support
- S10 family emergency impersonation

**Genuine** (★ = hard negative, which sounds scary but is real):
- G1 ★ delivery OTP
- G2 ★ bank EMI/bill reminder
- G3 ★ real bank fraud alert
- G4 ★ real police/court/passport verification
- G5 ★ family member asking for money
- G6 telemarketing/loan sales
- G7 everyday (doctor, school, landlord, plumber, service centre)
- G8 ★ someone describing a scam / warning a friend
- G9 ★ real customer support

### 4.2 Tactics: label **manipulation**, not keywords
| Tactic | Label it when the caller… | Don't label it when… |
|---|---|---|
| `authority_claim` | says they are from the police, a bank, a government body, a court or a company. **Label it in scam and genuine calls alike**: it's a fact about the call, not proof of a scam. It only matters in combination with the other tactics | the caller is a private person (a friend, relative or neighbour) |
| `threat` | warns of arrest, account block, disconnection, fines, a case, or harm to a relative, **to pressure** | a real reminder states a factual late fee in a calm, informative way |
| `urgency` | pushes for action now ("within 10 minutes", "immediately", "last chance") | ordinary scheduling ("I'll come at 4 pm") |
| `secrecy_isolation` | says don't tell family/bank, go to a separate room, keep it confidential | a family member jokes "don't tell dad" |
| `credential_request` | asks for an OTP, PIN, CVV, password or card number **to take control** | a delivery agent asks for the delivery OTP; real support asks you to confirm your registered email |
| `remote_access` | asks you to install a screen-sharing app, share the screen, or accept a remote request | a support agent warns you **not** to install apps |
| `payment_instruction` | tells you to send money to an account/UPI/QR they give, or to pay a "fee" to receive money | a landlord reminds you of rent to the usual account; COD payment to a delivery agent |
| `stay_on_line` | says don't hang up, stay on the (video) call, "digital arrest" | — |

Rule of thumb: ask "is this sentence trying to **push** the listener against their own interest?" If yes, label it. If it's informative, don't.

### 4.3 First suspicious caller turn
The first caller turn (0-based, counting caller turns only) at which **a careful, informed person should become suspicious**. Usually it's the first threat, secrecy demand, credential/remote/payment request, or an absurd claim (e.g. "your Aadhaar was used for money laundering"). An authority claim alone (turn 0 "I am from SBI") is **not** yet suspicious.
This drives the **earliness** metric, so be consistent: when unsure, pick the later turn, and write a note in `review/labelling_notes.md`.

### 4.4 Double-labelling
Every lockbox call is checked by a **second member**, who labels type, first suspicious turn and tactics without looking at the first labels. Disagreements are settled by discussion and logged in `review/labelling_notes.md`. Report agreement (% calls with the same first suspicious turn ±1, tactic agreement) in the datasheet.

---

## 5. Lockbox: writing human test calls

**Why it matters:** this is the only set where accuracy means something. LLM-written test calls are too similar to LLM-written training calls.

### 5.1 Quotas per member (4 members → 160 + 160)
- **40 scam calls:** 4 of each type S1–S10. Of those 40, **at least 5 polite/slow scams** with no threats and no obvious keywords (e.g. a soft-spoken "refund" executive who just asks you to install an app).
- **40 genuine calls,** including **at least 20 hard negatives**:
  - 4× G1 delivery OTP
  - 3× G3 real bank fraud alert
  - 3× G4 real police/passport verification
  - 4× G5 family asking for money
  - 3× G8 describing a scam
  - 3× G9 real support
  - the other 20 from G2, G6, G7 and any ★ type
- Length: 6–20 turns (3–10 caller turns). Mix short and long.

### 5.2 How to write
- **Your own words.** Base calls on public advisories (RBI, cybercrime.gov.in, I4C, bank awareness pages, news) and real experience (family members' stories), but **never copy text**.
- **Messy, spoken English:** fillers ("uh", "see", "madam", "actually"), half sentences, repetition, interruptions, Indian-English phrasing ("kindly", "do the needful", "what is your good name").
- **Vary victims:** a confused senior, a sceptical student, a panicked professional, a busy shop owner. Some victims resist; some comply.
- **Vary scammers:** slow build, instant panic, friendly then threatening, polite.
- Write **as Whisper would hear it**: no stage directions, no emojis, numbers as spoken ("fifty thousand rupees" or "₹50,000"; both are fine).
- **Isolation rule:** whoever writes lockbox calls must **not** read the LLM prompts in `backend/README.md` §4.3 or the generated training data before finishing. If someone has already seen them, they do review and red-team work instead.

### 5.3 Freeze procedure
1. Merge everyone's calls into `lockbox/lockbox_v1.jsonl`, run the validator, and complete double-labelling.
2. Compute the hash:
   - Windows: `certutil -hashfile data-eval\lockbox\lockbox_v1.jsonl SHA256`
   - Mac/Linux: `sha256sum data-eval/lockbox/lockbox_v1.jsonl`
3. Record the date, hash, counts per type and authors in `results/lockbox_log.md`, commit, and tell Backend.
4. From then on **the file never changes**. A fix means a new `lockbox_v2.jsonl` with a new hash and a note explaining why.
5. Backend evaluates the lockbox **once per frozen model version**. Nobody tunes anything after seeing lockbox results.

---

## 6. Red-team set (150 scam + 100 genuine)

Written to break the model. Anyone can write these, including people who saw the prompts.

| Category | Scam | Genuine | Example idea |
|---|---|---|---|
| Polite, slow scams with no scary words | 40 | — | "Madam, just to process your refund, kindly open the support app…" |
| Paraphrased / misspelled known lines | 40 | — | "one time pass code", "any-desk", "digi arrest" (as Whisper might mishear) |
| Scams that sound like hard negatives (calm "bank officer") | 30 | — | Mimics G3 tone but ends with "share the OTP to cancel the transaction" |
| New / rare scam angles | 40 | — | Electricity + QR refund, fake courier customs duty, fake job offer fee |
| Genuine calls that sound scary | — | 60 | Real police summons, real late-payment notice, real bank block |
| Genuine calls full of scam words | — | 40 | A news discussion about digital arrest; a teacher warning students |

Save to `redteam/redteam_v1.jsonl`, with a `category` field added to each call.

---

## 7. Real-audio set: recording protocol

**Purpose:** measure how much accuracy drops when real voices go through a real phone speaker, a real room and the app's Whisper.

### 7.1 Consent (before any recording)
Every speaker signs or messages agreement to this text (keep the agreements in a private folder, **not** in Git):
> "I agree to role-play scripted phone calls for the BOSC hackathon project. The audio is used only to produce text transcripts for testing, and is deleted afterwards. No real personal details are used."

### 7.2 Scripts
- Write **new** scripts for these 80 calls (40 scam, 40 genuine; same type mix as the lockbox). Don't reuse lockbox calls.
- Save each clean script in the call format as `realaudio/scripts_v1.jsonl` (the "clean text" reference).

### 7.3 Recording run (with Frontend's deployed app)
1. Phone A = caller (teammate reading the script). Phone B = BOSC.
2. Conditions, spread evenly across the 80 calls:
   - **quiet room**, two-phone call;
   - **noisy room** (fan or background chatter), two-phone call;
   - **speakerphone + "This mic"**: the call is on speaker on another phone and Phone B listens.
3. Phone B's summary screen → **Download session log** → rename to `ra-<id>.jsonl` and save it to a private folder.
4. If any extra audio was recorded for checking, **delete it the same day**. Keep transcripts only.

### 7.4 Session log → call format
The app's session log has one JSON line per caller utterance:
```json
{"t": 5321, "text": "...", "p": 0.42, "risk": 0.31, "level": "SAFE", "tactics": ["threat"], "asrMs": 1250, "dur": 3.8}
```
It may also contain event lines like `{"t": 40210, "event": "user_override_pay"}`.

Write `tools/log_to_call.py`. It should:
1. take a session log + the call's clean script (for `label`, `type`, `first_suspicious_caller_turn`);
2. output one call whose caller turns are the **app's transcribed utterances**, with no receiver turns, which is fine because the model only sees the caller;
3. set `"asr": true`, `"condition": "quiet|noisy|speaker"`, and keep `clean_ref_id` pointing to the clean script.

**Turn alignment:** Whisper may merge or split sentences. Set `first_suspicious_caller_turn` on the **transcribed** turns by finding the first utterance that contains the suspicious content, by hand if needed.

Output: `realaudio/realaudio_v1.jsonl` (transcripts) + `realaudio/scripts_v1.jsonl` (clean). Backend reports the **real-audio drop** = call F1 on transcripts − call F1 on clean scripts.

---

## 8. Reviewing Backend's LLM-generated data

Backend generates ~10,000 training calls with an LLM (`backend/README.md` §4.3–4.4). You check quality, **after** you've finished writing lockbox calls.
- **Sample:** 5% of all calls plus 10% of hard negatives (Backend sends the list).
- **For each call, record in `review/llm_review.csv`:** `id, ok (y/n), problem, fix`. Problems to look for:
  - **wrong label** (a "genuine" call that is actually a scam, or the reverse);
  - **wrong first suspicious turn** (off by more than 1);
  - **tactic labels on non-manipulative lines** (e.g. a delivery OTP labelled `credential_request`);
  - **unrealistic speech** (too formal, too long, no fillers);
  - **US flavour** (SSN, dollars, IRS) in the Indian set;
  - **near-duplicates** of other calls;
  - **real personal data.**
- If more than 10% of a type fails, tell Backend to fix the prompt for that type and regenerate it.

---

## 9. Demo scripts (`frontend/demo/scripts.json`)

Format: `{"id": "...", "title": "...", "kind": "scam"|"genuine", "lines": ["caller line 1", ...]}`. Only caller lines go in; Phone A reads them aloud.

**Rules**
- Each **scam** script must cross **PAUSE before the money/OTP/app request line**, so the payment pause is visible at the right moment.
- Each **genuine** script must stay **Safe** throughout.
- Lines must be natural to say aloud in ~5–8 seconds each.

**Check after every edit:**
```bash
python backend/check_scripts.py
```
It prints each script's risk per line (S/W/P = Safe/Warn/Pause). Current results:
- digital arrest reaches P at line 4 of 7;
- KYC OTP reaches P at line 3 of 5;
- polite refund reaches P at line 4 of 4;
- the delivery OTP, bank alert and clinic scripts stay S.

Then test them **spoken** on the phones (Whisper may change words) with Frontend.

**Main stage scripts:** "Digital arrest" (scam), then "Delivery OTP" (genuine). Rehearse both until they're natural.

---

## 10. Evaluation: what Backend computes, what you analyse

**Backend** runs `backend/evaluate_all.py` and produces:
- `results/ablation.md` (rows A–G);
- metrics with 95% confidence intervals;
- figures (`results/fig_*.png`);
- `results/errors_raw.jsonl` (every lockbox miss and false alarm, with the risk trace).

**You:**
1. **Error analysis** (`results/errors.md`). Read every miss and false alarm, group them into categories, count them, and suggest a fix for each group. Starting categories (add your own):
   - *Missed scams:* polite with no threats · scam ends before the ask · unusual scam type · Whisper mangled the key words · very short call.
   - *False alarms:* real bank alert mentions OTP/block · family money request · scam-warning conversation · police verification · telemarketing pressure.
   Mark each group **fixed** (after a later model) or **open**. Open groups go on the limits slide.
2. **Choose the slide figures:** the risk-over-time plot with the payment moment marked, the trimmed ablation table (rows A, B, E, G), and per-type recall if it tells a story.
3. **Sanity-check the numbers:** sizes match the lockbox log; CIs are present; no number comes from a synthetic set without saying so.
4. **Keep the logs:** `results/lockbox_log.md` (every lockbox evaluation: date, model hash, lockbox hash, headline numbers), `results/frontend_tests.md` and `results/latency.md` (from Frontend).

**Headline numbers** (state them with CIs and sizes):
1. recall @PAUSE on the lockbox;
2. false alarm @PAUSE on the lockbox;
3. median earliness;
4. real-audio drop.

---

## 11. Slides and the 3-minute pitch

### 11.1 Deck outline (≈ 10 slides)
| # | Slide | Content |
|---|---|---|
| 1 | Title | BOSC: Bane of Scam Calls · team · one line: "Catches the pressure on the call before the money leaves" |
| 2 | Problem | Payment scams are conversations: fake police/bank, panic, the victim pays themselves. The payment app sees a normal payment. Add **one sourced statistic** (I4C / NCRP / RBI annual report). Write the source on the slide, and only use a number you have actually looked up. |
| 3 | Insight | The only evidence is the pressure on the call, and nobody connects it to the payment moment |
| 4 | Solution | One app: listens on-device → risk meter + warning → pauses risky payments. Diagram: call → speech-to-text → detector → Shield |
| 5 | Live demo | (switch to the phones) |
| 6 | How it works | Whisper in the browser → tactic detection + trained model → streaming risk → payment pause. "Audio never leaves the phone." |
| 7 | Results | Ablation table (A, B, E, G) + risk-over-time plot + 3 headline numbers with CIs + dataset sizes |
| 8 | Privacy | On-device, nothing recorded or stored, mic only during calls, user always in control |
| 9 | Honest limits | OS blocks call audio for normal apps → built-in to calling apps / our app; English only; synthetic training data, human lockbox for testing; open error groups |
| 10 | Path to impact | Who benefits (seniors, families, fintechs: fewer disputes/refunds); SDK for payment apps; pilots; partners (calling apps, phone makers, carriers) |
| 11 | Disclosure | Pre-trained models (Whisper, encoders), public dataset (BothBosu, Apache-2.0), LLM-generated training data, and what was pre-built before the event (per the organisers' rules) |

### 11.2 Demo script (3:00)
| Time | What happens | Who |
|---|---|---|
| 0:00–0:20 | Hook: one sentence on digital-arrest/OTP scams and why the payment app can't see the call | Speaker |
| 0:20–1:20 | **Live call:** the teammate reads "Digital arrest". The meter climbs, chips light up, the warning appears | Caller + BOSC phone on the projector/visualiser |
| 1:20–2:00 | **Payment moment:** tap Pay ₹50,000 → **Payment paused** → "Call my trusted contact" | BOSC phone |
| 2:00–2:20 | **Genuine call:** two lines of "Delivery OTP" stay green; the delivery OTP isn't flagged | Caller |
| 2:20–2:50 | Results slide: 3 numbers + one sentence | Speaker |
| 2:50–3:00 | Limits in one sentence + thank you | Speaker |

### 11.3 Rehearsal
- At least **3 full timed rehearsals**, including **one with a forced fallback** (Frontend guide §11: switch to "This mic" or the video).
- Agree a hand signal for "switch to fallback", and decide in advance who says what if it happens.
- Record one perfect run as the backup video (with Frontend).

---

## 12. Q&A preparation

Prepare a one-line answer with evidence for each:
- **Why not keyword rules?** Ablation row A vs G on lockbox hard negatives.
- **Is the accuracy real?** Human-written lockbox (sizes), real recordings, CIs, failures listed honestly.
- **Does it work on a real phone call?** Mobile OSes block call audio for normal apps, so BOSC works inside calling apps (our demo app); the payment check works without audio. Be honest about this.
- **Privacy / legality?** On-device, nothing stored, mic only during calls. Mention India's DPDP Act 2023 and call-recording consent, and that a real deployment needs a legal review.
- **What if a genuine call is flagged?** Calibrated thresholds, "I trust this call" override, a pause rather than a block.
- **What if scammers adapt?** Leave-one-type-out results, scam-library updates, retraining. It's an arms race.
- **Why would a payment app adopt it?** One extra signal, no audio needed by the payment app, fewer disputes and refunds.
- **Competitors?** Spam blockers know numbers, not conversations; bank alerts arrive after the money has gone; phone-maker scam detection exists in some regions. **Check current competitor features before the event and state the difference accurately.**
- **Other languages?** English only now; Hinglish/Hindi/Tamil are on the roadmap with per-language evaluation.
- **Voice-cloned relatives?** Out of scope; future work for the family-emergency type.

Write the answers in `slides/qa.md` and practise them in rehearsals.

---

## 13. Rules that protect our credibility
1. **Never headline a synthetic number.** Synthetic results are always labelled "synthetic".
2. **The lockbox is untouchable:** no training, no tuning, one evaluation per frozen model, every evaluation logged.
3. **Every number has its size and CI** ("160 scam + 160 genuine human-written calls").
4. **List failures.** Judges trust a team that shows where it's weak.
5. **Consent and privacy:** no real victims' recordings, no real personal data, no audio kept.
6. **Ask the organisers in writing** whether pre-trained models, pre-built datasets and pre-written code are allowed. Keep the reply and put the disclosure slide in the deck.
7. **Statistics need sources** on the slide. If you can't find a source, leave the number out.

---

## 14. Checklist, timeline and done criteria

**Checkpoint 1**
- [ ] `tools/validate_calls.py` written
- [ ] First 100 lockbox calls written and double-labelled
- [ ] Organiser rules asked in writing
- [ ] Deck skeleton (titles only) + Q&A list

**Checkpoint 2**
- [ ] Lockbox 160 + 160 complete, validated, double-labelled, **hashed and logged**, handed to Backend
- [ ] Red team 150 + 100 complete
- [ ] Real-audio recordings done (80 calls, 3 conditions); `tools/log_to_call.py` written; `realaudio_v1.jsonl` + `scripts_v1.jsonl` ready; audio deleted
- [ ] LLM data review done; problems sent to Backend
- [ ] Demo scripts checked (text + spoken)

**After the model freeze (Day −2)**
- [ ] Backend's final evaluation received; numbers sanity-checked
- [ ] `results/errors.md` complete
- [ ] Results, limits and disclosure slides final
- [ ] 3 timed rehearsals (one with a forced fallback); backup video recorded

**Done when** the deck's headline numbers come from the frozen model on the **lockbox and real audio** (with sizes and CIs), every miss and false alarm is categorised, and the team can deliver the 3-minute pitch and answer every §12 question without notes.
