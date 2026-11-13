// "Android mode": Android does not let apps record the other side of a phone call, only this phone's
// microphone. So BOSC listens to the VICTIM's own words and looks for dangerous replies.
// Rules are deliberately simple (regex), English only.

const DIGIT_WORD = "(?:zero|oh|one|two|three|four|five|six|seven|eight|nine)";

export const VICTIM_CUES = {
  code_readout: {
    label: "You read out a code",
    patterns: [
      /\b\d{6}\b/,                                   // 6-digit OTP said in one go ("452189")
      /\b\d(?:[\s,.-]+\d){3,7}\b/,                   // digit by digit ("4 5 2 1 8 9")
      new RegExp(`\\b${DIGIT_WORD}(?:[\\s,.-]+${DIGIT_WORD}){3,}\\b`, "i"),
      /\b(?:otp|code|pin|password|cvv)\b[^.?!]{0,15}\b(?:is|was)\b\s*\d/i,
    ],
  },
  shared_secret: {
    label: "You gave a PIN / card detail",
    patterns: [
      /\bmy (?:upi |atm |card |m)?(?:pin|otp|cvv|password)\b/i,
      /\b(?:card|account|aadhaar|aadhar) number is\b/i,
    ],
  },
  remote_app: {
    label: "You installed / opened a remote app",
    patterns: [
      /\b(?:install(?:ed|ing)?|download(?:ed|ing)?|open(?:ed|ing)?)\b[^.?!]{0,25}\b(?:any ?desk|team ?viewer|quick ?support|rust ?desk|screen ?shar\w*)/i,
      /\b(?:shar(?:ing|ed)) (?:my|the) screen\b/i,
    ],
  },
  agreeing_to_pay: {
    label: "You agreed to pay",
    patterns: [
      /\b(?:i'?ll|i will|let me|i am|i'm|okay,? i'?ll)\s+(?:send|sending|transfer|transferring|pay|paying)\b/i,
      /\b(?:i )?(?:have |'ve )?(?:sent|transferred|paid) (?:the |it|money|rs|rupees|₹|\d)/i,
    ],
  },
  agreeing_to_secrecy: {
    label: "You agreed to keep it secret",
    patterns: [/\b(?:i )?(?:won'?t|will not|didn'?t|did not) tell (?:anyone|anybody|my |them|him|her)/i],
  },
  fear: {
    label: "You sound frightened",
    patterns: [
      /\b(?:please )?(?:don'?t|do not) arrest\b/i,
      /\bam i in (?:trouble|danger)\b/i,
      /\bi (?:didn'?t|did not) do anything\b/i,
      /\bi'?m (?:so )?(?:scared|afraid)\b/i,
    ],
  },
};

export function detectVictim(text) {
  const found = [];
  for (const [name, c] of Object.entries(VICTIM_CUES)) if (c.patterns.some((p) => p.test(text))) found.push(name);
  return found;
}

// Level from everything the victim has said so far in this call.
// A remote-control app is almost never legitimate on a call: PAUSE straight away.
// Reading out a code alone is WARN (a delivery OTP is normal); two different danger signs: PAUSE.
export function victimLevel(seen) {
  if (seen.has("remote_app") || seen.size >= 2) return "PAUSE";
  return seen.size ? "WARN" : null;
}

export function victimAdvice(seen) {
  if (seen.has("remote_app")) return "Close and uninstall that app now. Remote-control apps let the caller take over your phone and bank.";
  if (seen.has("code_readout") || seen.has("shared_secret")) return "Never read out an OTP, PIN or card number to a caller who asked for it. Hang up and call your bank.";
  if (seen.has("agreeing_to_pay")) return "Don't send money because of this call. Real police and banks never ask you to pay on a call.";
  if (seen.has("agreeing_to_secrecy")) return "A caller who asks you to keep secrets is a warning sign. Talk to your family first.";
  if (seen.has("fear")) return "No real officer arrests anyone over the phone. You can hang up.";
  return "";
}
