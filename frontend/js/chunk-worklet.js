// AudioWorklet tap: forwards mono input to the main thread in 2048-sample blocks.
// Output is left silent; the chunker on the main thread does the resampling and VAD.
const BLOCK = 2048;

class BoscChunkTap extends AudioWorkletProcessor {
  constructor() {
    super();
    this.buf = new Float32Array(BLOCK);
    this.n = 0;
  }
  process(inputs) {
    const ch = inputs[0] && inputs[0][0];
    if (ch) {
      for (let i = 0; i < ch.length; i++) {
        this.buf[this.n++] = ch[i];
        if (this.n === BLOCK) {
          this.port.postMessage(this.buf, [this.buf.buffer]);
          this.buf = new Float32Array(BLOCK);
          this.n = 0;
        }
      }
    }
    return true;
  }
}

registerProcessor("bosc-chunk-tap", BoscChunkTap);
