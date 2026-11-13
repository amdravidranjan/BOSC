"""Phone-path TTS/VAD/Whisper augmentation; segment labels require review.

Audio and transcripts are ignored. This module never claims automatic time
alignment establishes the semantic tactic labels of a split/garbled utterance.
"""
import argparse
import asyncio
import copy
import hashlib
import importlib.metadata
import json
import subprocess
from pathlib import Path

import edge_tts
import imageio_ffmpeg
import numpy as np
import sherpa_onnx
import soundfile as sf
import onnx
from scipy.signal import butter, sosfilt

from common import ART, LABELS, write_json

ASSETS=ART/'v2/asr'
VOICES=('en-IN-NeerjaNeural','en-IN-PrabhatNeural')
JUNK={'[blank_audio]','(speaking in foreign language)','thank you','you'}


def junk(text):
    return text.strip().lower().rstrip('.!? ') in JUNK or not text.strip()


def phone_audio(samples,path,snr,volume,seed):
    if path not in ('A','B') or snr not in (10,20) or volume not in (-6,6):
        raise ValueError('Unexpected phone-path parameters')
    x=np.asarray(samples,dtype=np.float32)
    if path=='B':
        ff=imageio_ffmpeg.get_ffmpeg_exe()
        encoded=subprocess.run([ff,'-v','error','-f','f32le','-ar','16000','-ac','1','-i','pipe:0',
                                '-ar','8000','-f','alaw','pipe:1'],input=x.tobytes(),capture_output=True,check=True).stdout
        decoded=subprocess.run([ff,'-v','error','-f','alaw','-ar','8000','-ac','1','-i','pipe:0',
                                '-ar','16000','-f','f32le','pipe:1'],input=encoded,capture_output=True,check=True).stdout
        x=np.frombuffer(decoded,dtype=np.float32)
        x=sosfilt(butter(4,[300,3400],btype='bandpass',fs=16000,output='sos'),x)
    rng=np.random.default_rng(seed)
    noise=rng.normal(size=len(x)); rms=np.sqrt(np.mean(x*x))
    noise*=rms/(10**(snr/20)*max(np.sqrt(np.mean(noise*noise)),1e-12))
    x=(x+noise)*10**(volume/20)
    # Match signed 16-bit PCM before inference, including real clipping.
    return (np.clip(x,-1,32767/32768)*32768).astype(np.int16).astype(np.float32)/32768


def recognizer():
    if importlib.metadata.version('sherpa-onnx')!='2.0.1':
        raise RuntimeError('Requires sherpa-onnx==2.0.1')
    for name in ('tiny.en-encoder.int8.onnx','tiny.en-decoder.int8.onnx','tiny.en-tokens.txt','silero_vad.onnx'):
        if not (ASSETS/name).is_file():
            raise FileNotFoundError('Finish v2_download_asr.py before ASR: '+name)
    encoder=onnx.load(str(ASSETS/'tiny.en-encoder.int8.onnx'),load_external_data=False)
    frames=encoder.graph.input[0].type.tensor_type.shape.dim[2]
    if frames.HasField('dim_value'):
        raise ValueError('Fixed-length Whisper export found. Verify the phone revision; do not silently change padding.')
    return sherpa_onnx.OfflineRecognizer.from_whisper(
        encoder=str(ASSETS/'tiny.en-encoder.int8.onnx'),decoder=str(ASSETS/'tiny.en-decoder.int8.onnx'),
        tokens=str(ASSETS/'tiny.en-tokens.txt'),language='en',task='transcribe',num_threads=2)


def transcribe(samples,rec):
    cfg=sherpa_onnx.VadModelConfig()
    cfg.silero_vad.model=str(ASSETS/'silero_vad.onnx')
    cfg.silero_vad.threshold=.5
    cfg.silero_vad.min_silence_duration=.4
    cfg.silero_vad.min_speech_duration=.25
    cfg.silero_vad.max_speech_duration=8.
    cfg.sample_rate=16000
    vad=sherpa_onnx.VoiceActivityDetector(cfg,buffer_size_in_seconds=max(30,len(samples)/16000+5))
    size=cfg.silero_vad.window_size; segments=[]
    padded=np.pad(samples,(0,size-len(samples)%size+16000))
    def drain():
        while not vad.empty():
            item=vad.front; audio=np.asarray(item.samples,dtype=np.float32).copy(); start=int(item.start)
            vad.pop()
            stream=rec.create_stream(); stream.accept_waveform(16000,audio)
            rec.decode_stream(stream)
            text=stream.result.text.strip()
            segments.append({'start_sample':start,'end_sample':start+len(audio),'text':text,'dropped':junk(text)})
    for i in range(0,len(padded),size):
        vad.accept_waveform(padded[i:i+size]); drain()
    vad.flush(); drain()
    return segments


async def synthesize(call,folder,voice,rate,pitch):
    folder.mkdir(parents=True,exist_ok=True)
    audio=[]; spans=[]; offset=0
    for i,turn in enumerate(t for t in call['turns'] if t['speaker']=='caller'):
        key=hashlib.sha256(json.dumps([turn['text'],voice,rate,pitch]).encode()).hexdigest()
        wav=folder/(key+'.wav')
        if not wav.exists():
            mp3=folder/(key+'.mp3')
            await asyncio.wait_for(edge_tts.Communicate(turn['text'],voice,rate=rate,pitch=pitch).save(str(mp3)),timeout=60)
            subprocess.run([imageio_ffmpeg.get_ffmpeg_exe(),'-v','error','-y','-i',str(mp3),
                            '-ar','16000','-ac','1','-c:a','pcm_s16le',str(wav)],check=True)
        x,sr=sf.read(wav,dtype='float32'); assert sr==16000
        spans.append({'caller_turn':i,'start_sample':offset,'end_sample':offset+len(x)})
        audio.extend([x,np.zeros(8000,dtype=np.float32)]); offset+=len(x)+8000
    return np.concatenate(audio),spans


def align(call,segments,spans,path):
    out=copy.deepcopy(call); out.update(id=call['id']+'-asr-'+path,parent_id=call.get('parent_id',call['id']),
        asr=True,phone_path=path,annotation_status='needs_segment_review',turns=[])
    fs=call['first_suspicious_caller_turn']; suspicious=[]
    out['call_tactics']=sorted({k for t in call['turns'] for k in t.get('tactics',[])},key=LABELS.index)
    for seg in segments:
        if seg['dropped']: continue
        overlaps=[s['caller_turn'] for s in spans if min(s['end_sample'],seg['end_sample'])>max(s['start_sample'],seg['start_sample'])]
        after=fs is not None and overlaps and min(overlaps)>=fs
        crosses=fs is not None and overlaps and min(overlaps)<fs<=max(overlaps)
        suspicious.append(bool(after))
        out['turns'].append({'speaker':'caller','text':seg['text'],'tactics':[],
                            'tactics_known':False,'scam_label_known':bool(overlaps) and not crosses,
                            'source_caller_turns':overlaps,'start_sample':seg['start_sample'],'end_sample':seg['end_sample']})
    out['first_suspicious_caller_turn']=next((i for i,v in enumerate(suspicious) if v),None)
    out['alignment_note']='Time provenance only. Human review must rederive semantic tactic labels and suspicious boundary before training.'
    return out


async def main_async(args):
    cs=[json.loads(s) for s in args.input.read_text().splitlines() if s.strip()]
    if args.limit: cs=cs[:args.limit]
    rec=recognizer(); outdir=ART/'v2/asr_copies'; outdir.mkdir(parents=True,exist_ok=True)
    for call in cs:
        if not call['turns']: raise ValueError('Empty input call')
        seed=int(hashlib.sha256(call['id'].encode()).hexdigest()[:8],16)
        voice=VOICES[seed%2]; rate=('-10%','+0%','+10%')[seed%3]; pitch=('-5Hz','+0Hz','+5Hz')[(seed//3)%3]
        audio,spans=await synthesize(call,ART/'v2/tts',voice,rate,pitch)
        for path in ('A','B'):
            snr=(20,10)[seed%2]; volume=(-6,6)[(seed//2)%2]
            x=phone_audio(audio,path,snr,volume,seed)
            segments=transcribe(x,rec)
            result=align(call,segments,spans,path)
            result['audio_config']={'voice':voice,'rate':rate,'pitch':pitch,'snr_db':snr,'volume_db':volume,'seed':seed}
            ident=hashlib.sha256(call['id'].encode()).hexdigest()
            write_json(outdir/(ident+'-'+path+'.json'),{'call':result,'segments':segments})
            print(call['id'],path,'segments',len(segments),'kept',len(result['turns']),flush=True)


if __name__=='__main__':
    ap=argparse.ArgumentParser(); ap.add_argument('--input',type=Path,required=True); ap.add_argument('--limit',type=int)
    asyncio.run(main_async(ap.parse_args()))
