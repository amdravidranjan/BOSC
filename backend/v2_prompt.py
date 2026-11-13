"""Provider-independent prompts. Provider/model/terms must be chosen before use."""
import json
from common import LABELS

TYPES={
 'S1':'fake bank KYC or polite card upgrade asking for credentials',
 'S2':'digital arrest by impersonated police or cybercrime investigators',
 'S3':'remote access refund fraud','S4':'utility disconnection fraud','S5':'SIM or Aadhaar block fraud',
 'S6':'card or loan upfront fee fraud','S7':'task or guaranteed investment fraud',
 'S8':'prize or lottery fee fraud','S9':'fake technical support','S10':'impersonated family emergency asking for money',
 'G1':'genuine delivery OTP request','G2':'genuine bank EMI or card bill reminder',
 'G3':'genuine bank fraud alert; no credentials collected','G4':'genuine police, court or passport verification',
 'G5':'actual known family member asking for money','G6':'legal telemarketing or loan sales',
 'G7':'ordinary doctor, school, landlord or service call','G8':'describing or warning about a scam',
 'G9':'real customer support contacted by the customer'}


def prompt(scenario):
    common={'scenario':scenario,'type_description':TYPES[scenario['type_id']],
            'label_order':LABELS,'output_schema':{'turns':[{'speaker':'caller','text':'...','tactics':[]}],
                'label':scenario['label'],'type_id':scenario['type_id'],'first_suspicious_caller_turn':'zero-based caller index, null for genuine'}}
    if scenario['prompt_version']=='v1':
        instruction=('Write ONE realistic Indian phone conversation for a protective fraud classifier. '
            'Alternate caller and receiver, caller first. Follow the scenario and requested turn count. '
            'Use spoken fillers, interruptions and restarts. Tactics describe manipulation in each newest caller turn, '
            'not mere words. Delivery OTP requests and safe banking instructions are genuine. '
            'Do not annotate normal urgency as manipulation. Genuine calls cannot combine payment manipulation with threats. '
            'Polite scams must remain polite without threats and still contain a fraudulent request. ')
    else:
        instruction=('Create an original evaluation dialogue in the Indian setting described below. '
            'Return a JSON conversation, beginning with the caller, with alternating speakers and the specified length. '
            'Keep the dialogue natural, including hesitations and incomplete spoken sentences. '
            'Annotate only manipulation actually expressed in that caller sentence; previous sentences do not supply its labels. '
            'Distinguish a legitimate delivery code or routine bank reminder from fraudulent credential collection. '
            'A genuine call must not combine coercive payment instructions and threats. '
            'For the polite style, imply no threats: reveal fraud through the request and context. ')
    language=('Use romanised Hinglish with natural Hindi-English code switching and Latin letters only. '
              if scenario['language']=='hinglish' else 'Use Indian English. ')
    return instruction+language+('Use invented people and organisations only; no actual personal names, phone numbers, '
         'account numbers, addresses or actionable payment identifiers. Use placeholders for identifiers. '
         'Receiver turns have empty tactics. Mark the first suspicious caller turn based on observable content, '
         'not hindsight. Return only the JSON object.\n')+json.dumps(common,ensure_ascii=False)
