"""Template-based generator of English (Indian context) phone-call transcripts.

Scam types follow the plan's taxonomy S1-S10; genuine types include hard negatives
(calls that say OTP, bank, police, urgent, money for legitimate reasons).

Honesty mechanism: every phrase bank is split by index. Items with index % 4 == 3
are reserved for the TEST split, so test calls use phrasings never seen in training.
This is still synthetic data -- the headline number must come from the human-written
lockbox (data-eval/lockbox) and real recordings.

Usage: python gen_calls.py OUT_DIR
"""
import json
import random
import sys
from pathlib import Path

# ---------------------------------------------------------------- slot values
SLOTS = {
    "bank": ["SBI", "HDFC Bank", "ICICI Bank", "Axis Bank", "Canara Bank", "Indian Bank",
             "Punjab National Bank", "Bank of Baroda", "Kotak Bank", "Union Bank"],
    "agency": ["CBI", "Mumbai Cyber Crime cell", "Delhi Police", "the Customs department",
               "the Narcotics Control Bureau", "the Enforcement Directorate", "Crime Branch",
               "the Income Tax department", "Hyderabad cyber police", "TRAI"],
    "courier": ["FedEx", "DTDC", "Blue Dart", "India Post", "Delhivery", "DHL"],
    "app": ["AnyDesk", "TeamViewer QuickSupport", "RustDesk", "AirDroid", "the support app",
            "a screen sharing app"],
    "name": ["Rahul", "Priya", "Mr. Sharma", "Mrs. Lakshmi", "Arjun", "Kavitha", "Mr. Iyer",
             "Anita", "Suresh", "Meena", "Vikram", "Deepa"],
    "officer": ["Inspector Rajesh Kumar", "Officer Vinod Singh", "Sub Inspector Anil Verma",
                "DCP Sanjay Rao", "Officer Pradeep", "Inspector Mehta"],
    "agent": ["Ravi", "Sneha", "Karthik", "Pooja", "Imran", "Divya", "Manoj"],
    "city": ["Mumbai", "Delhi", "Chennai", "Bangalore", "Hyderabad", "Kolkata", "Pune"],
    "amount": ["5,000", "12,500", "49,999", "1,00,000", "2,50,000", "75,000", "18,000",
               "3,40,000", "9,999", "25,000", "499", "1,499"],
    "small_amount": ["499", "999", "1,299", "2,000", "3,500", "750", "1,850"],
    "minutes": ["10", "15", "30", "20", "5"],
    "relative": ["your son", "your daughter", "your nephew", "your grandson"],
    "store": ["Amazon", "Flipkart", "Myntra", "Swiggy", "a jewellery store", "Croma"],
    "day": ["tomorrow", "on Monday", "on the 5th", "this Friday", "on the 10th"],
}

# ------------------------------------------------------------- shared banks
# each entry: list of paraphrases (>= 4 so the test holdout is meaningful)
URGENCY = [
    "This has to be done immediately, we don't have much time.",
    "You have only {minutes} minutes to resolve this, after that it is out of my hands.",
    "Please do it right now, sir, every minute matters here.",
    "This is very urgent, it must be completed before today evening.",
    "We need this done in the next {minutes} minutes or the system will auto process it.",
    "Time is running out madam, you have to act fast.",
    "I am giving you a last chance, finish this right away.",
    "Do it quickly, the deadline is in a few minutes only.",
]
SECRECY = [
    "Do not tell anyone about this call, not even your family.",
    "This is a confidential investigation, you cannot discuss it with anybody.",
    "Don't inform your bank branch, they are also under investigation.",
    "Keep this matter secret, if you tell your family they will also be involved.",
    "Go to a separate room and close the door, nobody should hear this.",
    "This is a matter of national security, you are under a gag order.",
    "Please don't share this with your husband or children, it will create problems for them.",
    "Whatever I tell you stays between us, understood?",
]
STAY_ON_LINE = [
    "Do not disconnect this call until the verification is over.",
    "Stay on the line, if you cut the call it will be treated as non cooperation.",
    "Keep your camera on and remain on the video call, you are under digital arrest.",
    "Don't hang up, I am transferring you to my senior officer now.",
    "You must stay on this call till the process is complete, do not put the phone down.",
    "If the call disconnects, the case will be escalated automatically.",
    "Remain on the line, you are under digital surveillance from now.",
    "Don't end the call, keep the phone with you and keep talking to me.",
]
OTP_ASK = [
    "You will get an OTP now, please read it out to me.",
    "Tell me the six digit code you just received on your mobile.",
    "Share the OTP that came on your phone so I can verify your identity.",
    "Just confirm the verification code from the SMS, that's all I need.",
    "Please give me your UPI PIN for the verification process.",
    "Read out the card number and the CVV at the back of the card.",
    "An OTP has come from {bank}, kindly tell me that number quickly.",
    "Forward me the code you received, it is only for confirmation.",
]
REMOTE_ASK = [
    "Please install {app} from the Play Store, I will guide you.",
    "Download this app called {app}, it is our official support tool.",
    "Open {app} and tell me the nine digit number shown on the screen.",
    "I need you to share your screen so I can fix it from my side.",
    "Install the application I am sending on WhatsApp and give all permissions.",
    "Just download {app}, our technician will take remote access and do everything.",
    "Open the app and press accept when the screen sharing request comes.",
    "Allow the permissions on {app} so that I can see your screen.",
]
PAY_ASK = [
    "Transfer the amount to the safe account I am giving you, it will be refunded after verification.",
    "Send Rs {amount} to this UPI ID immediately for clearance.",
    "Scan the QR code I am sending and enter the amount, then the money will come to you.",
    "You need to pay a processing fee of {small_amount} rupees to release the amount.",
    "Move all your savings to the RBI verification account, it is fully secure.",
    "Do an IMPS transfer of {amount} to the account number I will give you now.",
    "Pay the refundable charge of {small_amount} through GPay to this number.",
    "Deposit the money to the government escrow account, after investigation you get it back.",
]

# ------------------------------------------------------------- scam types
# stage: (tactics, bank). Order of stages = typical script order.
SCAMS = {
    "S1_bank_kyc": {
        "open": [
            "Hello, I am calling from {bank} head office, KYC department.",
            "Good morning, this is {agent} from {bank} customer verification team.",
            "Hello sir, I am a bank officer from {bank}, your account manager.",
            "Madam, this call is from {bank} regarding your savings account.",
            "Hi, I am calling on behalf of {bank} security team.",
        ],
        "problem": [
            "Your KYC has expired and needs to be updated today.",
            "Your PAN card is not linked with your account, so we have to update the KYC.",
            "We noticed your account details are incomplete in our system.",
            "There is a mismatch in your Aadhaar details with the bank records.",
            "Your debit card has been flagged for an RBI compliance check.",
        ],
        "threat": [
            "If it is not updated your account will be blocked by evening.",
            "Otherwise your account will be frozen and all transactions will stop.",
            "Your debit card will be deactivated and the balance will be on hold.",
            "If you don't complete this the bank will suspend your account permanently.",
            "Failing this, a penalty of {small_amount} rupees will be charged and the account closed.",
        ],
        "asks": ["otp", "otp", "pay", "remote"],
    },
    "S2_digital_arrest": {
        "open": [
            "This is {officer} from {agency}.",
            "I am calling from {agency}, {city} branch, please listen carefully.",
            "Hello, {officer} speaking, {agency}. Is this {name}?",
            "This call is from {agency} regarding a serious case against you.",
            "Good afternoon, I am a senior officer with {agency}.",
        ],
        "problem": [
            "A parcel in your name was seized at {city} airport with drugs and fake passports.",
            "Your Aadhaar number has been used to open bank accounts for money laundering.",
            "A case has been registered against you for illegal transactions.",
            "Your SIM card is linked to a crime under investigation.",
            "We have found your name in a money laundering case involving a big businessman.",
        ],
        "threat": [
            "An arrest warrant has been issued in your name.",
            "If you don't cooperate, police will come to your house within two hours.",
            "You will be arrested and your bank accounts will be frozen.",
            "This is a non bailable offence, you can go to jail for seven years.",
            "The magistrate has ordered your arrest unless you clear your name today.",
        ],
        "asks": ["stay", "secret", "pay", "pay"],
    },
    "S3_remote_refund": {
        "open": [
            "Hello, I am calling from {store} refund department.",
            "Hi, this is {agent} from the customer care of {store}.",
            "Good evening, this is regarding your recent order on {store}.",
            "I am calling from the {bank} refund cell.",
            "Hello madam, {store} support team here.",
        ],
        "problem": [
            "You were charged twice for your last order and we have to refund {amount} rupees.",
            "Your refund of {small_amount} rupees has failed and needs manual processing.",
            "There was a duplicate payment, so we will reverse it to your account.",
            "Your cancellation refund is pending because of a technical error.",
            "We want to process your cashback but the system needs your confirmation.",
        ],
        "threat": [
            "If it is not processed today, the refund will lapse.",
            "Otherwise the amount will be forfeited as per policy.",
            "The refund window closes in {minutes} minutes.",
            "After today, we cannot help you with this refund.",
            "If you don't do it now, the amount will go back to the merchant.",
        ],
        "asks": ["remote", "remote", "otp", "pay"],
    },
    "S4_utility": {
        "open": [
            "Hello, I am calling from the electricity board office.",
            "This is from the electricity department, regarding your connection.",
            "Hello sir, electricity office here, is this the consumer {name}?",
            "Good evening, I am a lineman supervisor from the power department.",
            "Hi, this is the billing section of your telecom provider.",
        ],
        "problem": [
            "Your last month bill is not updated in our system.",
            "Your previous bill payment did not reach us.",
            "Your meter details have not been updated with your mobile number.",
            "There is a pending due of {small_amount} on your connection.",
            "Your connection is showing as unpaid since last month.",
        ],
        "threat": [
            "Your electricity connection will be disconnected tonight at 9:30.",
            "Our team will come and cut the power supply in one hour.",
            "Your connection will be disconnected and reconnection will cost extra.",
            "If you don't pay now the supply will be stopped today itself.",
            "Your number will be deactivated in two hours.",
        ],
        "asks": ["pay", "remote", "pay"],
    },
    "S5_sim_aadhaar": {
        "open": [
            "This call is from TRAI, the telecom regulator.",
            "Hello, I am calling from the Aadhaar department, UIDAI.",
            "This is an automated call from the telecom department, press nine to talk to an officer.",
            "Hello, I am from your mobile network's head office.",
            "Good morning, this is the government telecom verification desk.",
        ],
        "problem": [
            "Your mobile number has been used for sending illegal messages.",
            "Multiple SIM cards are registered on your Aadhaar.",
            "Your Aadhaar is linked to a SIM used in harassment complaints.",
            "Your number has been reported for fraud by many people.",
            "Your SIM card verification is pending as per new government rules.",
        ],
        "threat": [
            "All your numbers will be blocked in two hours.",
            "Your SIM will be deactivated and a police complaint will be filed.",
            "Your Aadhaar will be suspended and you cannot use any bank services.",
            "We will transfer this to the cyber crime police for legal action.",
            "Your number will be disconnected permanently today.",
        ],
        "asks": ["stay", "otp", "pay"],
    },
    "S6_card_loan": {
        "open": [
            "Hello, I am calling from {bank} credit card division.",
            "Congratulations sir, you have a pre approved loan from our finance company.",
            "Hi, this is {agent} from {bank}, regarding a special offer on your card.",
            "Good afternoon, I am calling to increase your credit card limit.",
            "Hello madam, we are offering an instant loan of {amount} at zero interest.",
        ],
        "problem": [
            "Your loan is approved, only the final verification is pending.",
            "Your card limit can be doubled today itself.",
            "Your reward points worth {small_amount} are expiring today.",
            "We will waive your annual fee if you complete the process now.",
            "The loan amount will be credited in ten minutes after processing.",
        ],
        "threat": [
            "This offer is valid only for the next {minutes} minutes.",
            "If you don't complete it now, the approval will be cancelled.",
            "After today the points will lapse permanently.",
            "Only few slots are left, it will go to another customer.",
            "Otherwise the system will reject your profile for one year.",
        ],
        "asks": ["otp", "pay", "otp"],
    },
    "S7_task_investment": {
        "open": [
            "Hi, I am {agent} from a digital marketing company, are you interested in a part time job?",
            "Hello, we are hiring for work from home tasks, you can earn daily.",
            "Hi sir, I am an investment advisor with a SEBI registered firm.",
            "Hello, this is regarding the trading group you joined on WhatsApp.",
            "Good evening, I am calling about a guaranteed return investment plan.",
        ],
        "problem": [
            "You just have to like videos and you earn fifty rupees per task.",
            "Our members are making thirty percent returns every week.",
            "You have completed your free tasks, now you can do premium tasks.",
            "Your profit of {amount} is showing in your wallet but it is locked.",
            "This stock tip is from inside information, it will double in a week.",
        ],
        "threat": [
            "If you don't complete the task now, your earnings will be frozen.",
            "The slot closes today, after that you lose the profit.",
            "Your account will be blocked if the task is incomplete.",
            "The offer is only for the first ten people.",
            "If you withdraw now, you will lose everything.",
        ],
        "asks": ["pay", "pay", "secret"],
    },
    "S8_prize_lottery": {
        "open": [
            "Congratulations! You have won a lucky draw from {store}.",
            "Hello, I am calling from the KBC lottery department.",
            "Hi, your mobile number has been selected for a prize of {amount} rupees.",
            "Good news madam, you have won a car in our anniversary contest.",
            "Hello, this is the prize distribution office, you are a winner.",
        ],
        "problem": [
            "To release the prize, you have to pay the GST and registration charge.",
            "The prize money is ready, only the tax clearance is pending.",
            "We need to verify your bank account to transfer the prize.",
            "Your prize will be delivered after the processing fee is paid.",
            "The prize amount is with the RBI, they need a clearance fee.",
        ],
        "threat": [
            "If you don't claim it today, it will go to the next winner.",
            "The offer expires in {minutes} minutes.",
            "Otherwise the prize will be cancelled.",
            "This is the last date to claim, after that it is forfeited.",
            "If you delay, the tax department will take action on unclaimed prizes.",
        ],
        "asks": ["pay", "otp", "secret"],
    },
    "S9_tech_support": {
        "open": [
            "Hello, I am calling from Microsoft technical support.",
            "Hi, this is the security team of your mobile company.",
            "Hello sir, we detected a problem with your computer from our server.",
            "This is {agent} from the Google security department.",
            "Hi madam, I am calling from the antivirus support center.",
        ],
        "problem": [
            "Your device is infected with a dangerous virus.",
            "Hackers are accessing your bank account through your phone.",
            "Your phone is sending your data to a foreign server.",
            "Your computer license has expired and it is not secure.",
            "We see unusual login attempts on your account from another country.",
        ],
        "threat": [
            "If we don't fix it now, your bank details will be stolen.",
            "All your data will be deleted in a few minutes.",
            "Your account will be hacked and money will be lost.",
            "Your device will be permanently blocked.",
            "Hackers will empty your account tonight.",
        ],
        "asks": ["remote", "remote", "pay"],
    },
    "S10_family_emergency": {
        "open": [
            "Hello aunty, this is {relative_friend}, a friend of {relative}.",
            "Hello, I am calling from the hospital, {relative} has met with an accident.",
            "Hi, this is {officer}, we have detained {relative} at the police station.",
            "Hello, it's me, I'm calling from a friend's phone, my phone got broken.",
            "Hello uncle, your nephew is in trouble, please listen.",
        ],
        "problem": [
            "He has been caught in a fight and the police want to file a case.",
            "He needs an operation immediately and the hospital wants a deposit.",
            "I am stuck at the airport and my wallet is stolen.",
            "There was an accident and the other party wants money to settle.",
            "He was found with drugs, but we can settle it quietly.",
        ],
        "threat": [
            "If you don't send money now, they will put him in jail.",
            "The doctors will not start without the deposit.",
            "His career will be finished if a case is registered.",
            "I will be in big trouble if you don't help now.",
            "They will arrest him in one hour.",
        ],
        "asks": ["secret", "pay", "pay"],
    },
}
EXTRA_SLOTS = {"relative_friend": ["Rohit", "Vijay", "Sandeep", "Ajay"]}

# ------------------------------------------------------------- genuine types
GENUINE = {
    "G1_delivery_otp": [
        ["Hello, I am the {courier} delivery person, I am near your gate.",
         "Hi sir, delivery from {store}, I have reached your apartment.",
         "Madam, this is your delivery agent, which floor are you on?",
         "Hello, I have your parcel from {courier}, are you at home?",
         "Hi, delivery boy here, I am standing outside the building."],
        ["Please tell me the delivery OTP that came on your phone.",
         "Can you share the four digit delivery code from the app?",
         "For handing over the parcel I need the OTP sent by {store}.",
         "The order needs an OTP for delivery, please read it out.",
         "Please tell the code shown in your order page, then I will hand over."],
        ["It is cash on delivery, {small_amount} rupees, you can pay cash or scan my QR.",
         "It is already prepaid, no payment needed.",
         "You can pay by UPI on my scanner when I come up.",
         "Prepaid order, just the OTP is enough.",
         "Amount is {small_amount}, keep change ready if paying cash."],
        ["Okay I am coming up in two minutes.",
         "Thank you, please rate the delivery in the app.",
         "Okay, I will leave it with the security if you are not there.",
         "Please come down, I have other deliveries also.",
         "Done, thank you sir, have a nice day."],
    ],
    "G2_bank_emi_reminder": [
        ["Hello, I am calling from {bank} loan department.",
         "Good morning, this is a reminder call from {bank}.",
         "Hi, {agent} here from {bank} collections team.",
         "Hello madam, this is regarding your home loan with {bank}.",
         "This is a courtesy call from your credit card company."],
        ["Your EMI of {amount} is due on the 5th.",
         "Your credit card bill of {amount} is due in three days.",
         "Your last EMI was not debited because of insufficient balance.",
         "This is to remind you about the payment due {day}.",
         "Your loan instalment is pending since last week."],
        ["Please maintain sufficient balance to avoid a late payment charge.",
         "If it is delayed, a late fee will be added as per the agreement.",
         "Delayed payment may affect your credit score, so please pay on time.",
         "You can pay through the bank app or net banking or at the branch.",
         "Please pay before the due date to avoid penalty interest."],
        ["We will never ask for your OTP or PIN on a call.",
         "Please don't share your card details with anyone.",
         "If you have already paid, please ignore this call.",
         "For any query you can call the number on the back of your card.",
         "Thank you for banking with us."],
    ],
    "G3_bank_fraud_check": [
        ["Hello, this is the fraud monitoring team from {bank}.",
         "Hi, I am calling from {bank} card security department.",
         "Good evening, {bank} risk team here, calling about your card.",
         "Hello sir, this is an alert call from {bank}.",
         "This is {agent} from {bank} transaction monitoring."],
        ["Did you just make a transaction of {amount} at {store}?",
         "We saw an unusual international transaction on your card.",
         "There was an attempt to use your card in {city} a few minutes ago.",
         "We declined a suspicious payment of {amount} on your card.",
         "Can you confirm whether you made a purchase at {store} today?"],
        ["If it was not you, we will block the card immediately and issue a new one.",
         "We have temporarily blocked the card for your safety.",
         "If you don't recognise it, we will raise a dispute for you.",
         "Your money is safe, the transaction did not go through.",
         "A new card will be sent to your registered address in five days."],
        ["Please do not share any OTP or PIN with anyone, bank never asks for it.",
         "We will never ask you to transfer money to any account.",
         "You can also verify this by calling the number on the back of your card.",
         "Don't install any app if someone calls asking you to, that is a scam.",
         "If anyone calls asking for OTP, please disconnect and report to 1930.",
         "Remember, nobody from the bank will ask for your PIN, CVV or OTP.",
         "Our team never asks customers to move money to a safe account.",
         "If you want, cut this call and call us back on the official helpline.",
         "Never share the code you get by SMS with any caller, even if they say they are from the bank."],
    ],
    "G4_police_verification": [
        ["Hello, this is from the local police station, regarding your passport verification.",
         "Hi, I am the constable for your area, calling about the tenant verification form.",
         "Good morning, this is the police station, about the complaint you filed.",
         "Hello sir, court clerk here, regarding the hearing date of your case.",
         "Hi, I am calling from the police verification office for your job background check."],
        ["Please come to the station {day} with your original Aadhaar and address proof.",
         "We need to verify your address, I will visit your house {day} morning.",
         "Your complaint has been registered, the FIR number will come by SMS.",
         "The hearing is fixed {day}, please come with your lawyer.",
         "Please bring two photographs and the printout of the application."],
        ["There is no fee for this, don't pay anyone who asks.",
         "If you can't come, you can inform the station on the landline.",
         "You can check the status on the passport seva website.",
         "If you have any doubt, you can visit the station directly and ask.",
         "Bring your neighbour also as a witness for the verification."],
        ["Okay, thank you, see you {day}.",
         "That's all, thank you for your cooperation.",
         "Please keep your phone reachable for the verification.",
         "You will get an SMS once it is cleared.",
         "Okay madam, have a good day."],
    ],
    "G5_family_money": [
        ["Amma, it's me, I am calling from the hostel.",
         "Hi dad, are you free for two minutes?",
         "Hello appa, I need a small help.",
         "Hey mom, listen, I have to pay something urgently.",
         "Hi, it's your son, calling from college."],
        ["The exam fee has to be paid by today evening, it is {small_amount} rupees.",
         "My phone recharge got over and I need money for the bus ticket.",
         "The hostel fee deadline is tomorrow, can you send {amount}?",
         "I need money for the project materials, it is urgent.",
         "My friend's birthday is today, can you send some money?"],
        ["Please send it to my GPay number, the same one.",
         "You can transfer to my account, you have the details.",
         "Send it on PhonePe, I will pay the fee immediately.",
         "Just transfer it whenever you are free today.",
         "Can you send it now, otherwise the office will close.",
         "Send it to my usual number, the one saved as my name.",
         "Or I can come home on Sunday and take it, whatever is easy.",
         "If you are busy, ask amma to send it, no problem.",
         "You can verify with the college office if you want, they sent a circular."],
        ["Okay, I will call you after paying, bye.",
         "Thank you, I will come home this weekend.",
         "Don't tell dad, he will scold me for spending, I am joking.",
         "Okay, also tell grandma I said hi.",
         "Love you, talk to you at night."],
    ],
    "G6_telemarketing": [
        ["Hello sir, I am calling from {bank}, we have a pre approved personal loan offer for you.",
         "Hi madam, this is {agent} from an insurance company, do you have two minutes?",
         "Good afternoon, we are offering a new credit card with lifetime free.",
         "Hello, I am calling about a new broadband plan in your area.",
         "Hi, this is a call from a mutual fund company about SIP plans."],
        ["The interest rate is only ten point five percent and there is no processing fee this month.",
         "This plan gives life cover of one crore at a low premium.",
         "You get five percent cashback on all online shopping.",
         "It is 300 Mbps at just {small_amount} per month.",
         "You can start with only five hundred rupees per month."],
        ["If you are interested, our executive will visit your home with the documents.",
         "I can send the details on WhatsApp, you can read and decide.",
         "You can apply on our official website or visit the nearest branch.",
         "The offer is valid till the end of this month.",
         "No need to decide now, please think about it."],
        ["Okay sir, sorry for disturbing, thank you.",
         "Shall I call you back next week?",
         "I will remove your number from the list, thank you.",
         "Okay, have a nice day.",
         "Thank you for your time madam."],
    ],
    "G7_everyday": [
        ["Hello, this is the clinic, calling to confirm your appointment.",
         "Hi, this is your landlord, about this month's rent.",
         "Hello, I am the plumber, I am coming for the bathroom work.",
         "Good morning, this is your child's class teacher.",
         "Hello, I am calling from the car service centre."],
        ["Your appointment with the doctor is {day} at 10 am, please come fifteen minutes early.",
         "The rent of {amount} is due on the 1st, please transfer as usual.",
         "I will reach by four pm, please keep the water supply off.",
         "There is a parents meeting {day}, please come without fail.",
         "Your car is ready, the bill is {small_amount} rupees."],
        ["Please bring your old reports and the prescription.",
         "You can send it to my account like last month.",
         "Keep the old tap aside, I will replace it.",
         "Please sign the progress report and send it back.",
         "You can pay at the counter by card or UPI when you come."],
        ["Okay, see you then.",
         "Thank you, let me know if there is any problem.",
         "I will call you once I reach.",
         "Okay, bye.",
         "Thank you, have a good day."],
    ],
    "G8_scam_aware": [
        ["Hey, did you hear what happened to my uncle yesterday?",
         "Listen, I got a very strange call this morning.",
         "Hi, I wanted to warn you about something.",
         "Hello, are you free, I have to tell you something important.",
         "Hey, our society group is talking about a new fraud."],
        ["Someone called saying they are from {agency} and said a parcel with drugs was found.",
         "A man said my account will be blocked and asked me for the OTP.",
         "They told him he is under digital arrest and must stay on video call.",
         "The caller asked me to install {app} for a refund.",
         "They said police will arrest him if he doesn't transfer money to a safe account."],
        ["I immediately cut the call and reported it to 1930.",
         "Thankfully he called his son and found out it was a scam.",
         "I didn't share anything, I just hung up.",
         "Real police never ask you to transfer money or stay on a call.",
         "Banks never ask for OTP, so I knew it was fake."],
        ["So please be careful and tell your parents also.",
         "If anyone calls like that, just disconnect.",
         "Don't share OTP with anyone, okay?",
         "Let's tell the society group about this.",
         "Okay, just wanted to warn you, bye."],
    ],
    "G9_support_genuine": [
        ["Thank you for calling {bank} customer care, this is {agent}.",
         "Hello, thanks for calling the support line, how can I help you?",
         "Welcome to {store} customer support, my name is {agent}.",
         "Hi, you have reached the helpdesk, this is {agent} speaking.",
         "Good evening, telecom customer service, how may I help?"],
        ["For verification, please tell me your registered email or date of birth.",
         "Can you confirm the last four digits of your registered mobile number?",
         "I can see your complaint about the refund, let me check.",
         "Your refund was processed and will reach your account in five to seven days.",
         "I have raised a ticket, you will get an SMS with the ticket number."],
        ["Please note we will never ask for your OTP, PIN or password.",
         "You don't need to install any app, it will happen automatically.",
         "There is no charge for this service.",
         "If you get any call asking for OTP in our name, please don't share it.",
         "You can track the status on our website.",
         "Just a reminder, our executives never ask for OTP or card details.",
         "No payment is required from your side for the refund.",
         "If someone asks you to download a screen sharing app in our name, it is fraud.",
         "Please don't give your password to anyone, including our staff."],
        ["Is there anything else I can help you with?",
         "Thank you for your patience.",
         "Have a nice day.",
         "Please rate this call after it ends.",
         "Thank you for calling."],
    ],
}

RECEIVER = {
    "neutral": ["Hello?", "Yes, tell me.", "Okay.", "Who is this?", "Yes speaking.",
                "Hmm, okay.", "What is this regarding?", "Sorry, I didn't get you."],
    "panic": ["Oh my god, what should I do?", "Please sir, I haven't done anything.",
              "Please don't arrest me.", "Okay okay, I will do whatever you say.",
              "I am very scared, please help me.", "What? How is that possible?"],
    "doubt": ["How do I know you are really from there?", "Can I call you back on the official number?",
              "This sounds strange.", "Why can't I visit the branch?",
              "I will ask my son and call back.", "Send me something in writing."],
    "comply": ["Okay, tell me what to do.", "Okay, I am doing it.", "Yes, it has come.",
               "One minute, let me check.", "Okay, I have opened it.", "Fine, I will send it."],
    "genuine": ["Okay, sure.", "Yes, thank you.", "Oh okay, got it.", "Alright.",
                "Okay, I will do that.", "Thanks for letting me know.", "Fine, see you."],
}

FILLERS = ["uh", "um", "see", "listen", "actually", "basically", "okay", "sir", "madam", "you know"]

ASR_SWAPS = {
    "otp": ["OTP", "o.t.p", "OTB", "one time password"],
    "anydesk": ["any desk", "AnyDesk", "any disk", "Annie desk"],
    "aadhaar": ["Aadhar", "Adhar", "other card", "aadhaar"],
    "upi": ["UPI", "U.P.I", "you pay"],
    "kyc": ["KYC", "K.Y.C", "kyc"],
    "cbi": ["CBI", "C.B.I", "cbi"],
    "gpay": ["GPay", "G pay", "Google Pay"],
}


def fill(text, rng):
    def repl(key):
        pool = SLOTS.get(key) or EXTRA_SLOTS.get(key)
        return rng.choice(pool)
    out = text
    for _ in range(6):
        start = out.find("{")
        if start < 0:
            break
        end = out.find("}", start)
        key = out[start + 1:end]
        out = out[:start] + repl(key) + out[end + 1:]
    return out


def pick(bank, rng, split):
    """Pick a paraphrase respecting the holdout: test uses idx%4==3, dev idx%4==2, train the rest.
    Dev and test both contain phrasings the model never trained on, so thresholds picked on dev
    reflect generalisation rather than memorisation."""
    want = {"test": {3}, "dev": {2}}.get(split, {0, 1})
    pool = [b for i, b in enumerate(bank) if i % 4 in want]
    return rng.choice(pool or bank)


def noisify(text, rng, level):
    words = text.split()
    out = []
    for i, w in enumerate(words):
        if level > 0 and rng.random() < 0.06 * level and len(words) > 4:
            continue  # ASR drop
        lw = w.lower().strip(".,?!")
        for key, alts in ASR_SWAPS.items():
            if lw == key and rng.random() < 0.5:
                w = rng.choice(alts)
        out.append(w)
        if rng.random() < 0.05 * level:
            out.append(rng.choice(FILLERS))
    if level > 0 and rng.random() < 0.25 * level and len(out) > 3:
        out = out[:2] + out  # restart
    if level > 0 and rng.random() < 0.3:
        out.insert(0, rng.choice(FILLERS))
    return " ".join(out)


ASK_BANK = {"otp": (OTP_ASK, ["credential_request"]),
            "pay": (PAY_ASK, ["payment_instruction"]),
            "remote": (REMOTE_ASK, ["remote_access"]),
            "stay": (STAY_ON_LINE, ["stay_on_line"]),
            "secret": (SECRECY, ["secrecy_isolation"])}


def make_scam(type_id, rng, split, idx):
    spec = SCAMS[type_id]
    style = rng.choice(["slow_build", "instant_panic", "friendly_then_threat", "polite"])
    victim = rng.choice(["panic", "doubt", "comply", "neutral"])
    noise = rng.choice([0, 1, 2])
    stages = [("open", ["authority_claim"]), ("problem", [])]
    if style != "polite":
        stages.append(("threat", ["threat"]))
    if style in ("instant_panic", "friendly_then_threat") or rng.random() < 0.5:
        stages.append(("urgency", ["urgency"]))
    asks = list(spec["asks"])
    rng.shuffle(asks)
    asks = asks[: rng.randint(2, len(asks))]
    if style == "instant_panic":
        stages[1], stages[-1] = stages[-1], stages[1]
    for a in asks:
        stages.append(("ask:" + a, None))
    # extra pressure repetitions for longer calls
    if rng.random() < 0.5:
        stages.append(("ask:" + rng.choice(spec["asks"]), None))

    turns = []
    for name, tactics in stages:
        if name.startswith("ask:"):
            bank, tactics = ASK_BANK[name[4:]]
        elif name == "urgency":
            bank = URGENCY
        else:
            bank = spec[name]
        text = noisify(fill(pick(bank, rng, split), rng), rng, noise)
        turns.append({"speaker": "caller", "text": text, "tactics": tactics})
        turns.append({"speaker": "receiver", "text": rng.choice(RECEIVER[victim]), "tactics": []})
    caller_turns = [t for t in turns if t["speaker"] == "caller"]
    first_susp = next((i for i, t in enumerate(caller_turns)
                       if set(t["tactics"]) - {"authority_claim"}), len(caller_turns) - 1)
    return {"id": f"{split}-{type_id}-{idx}", "label": 1, "type": type_id, "style": style,
            "split": split, "source": "gen", "turns": turns,
            "first_suspicious_caller_turn": first_susp}


def make_genuine(type_id, rng, split, idx):
    banks = GENUINE[type_id]
    noise = rng.choice([0, 1, 2])
    turns = []
    order = list(range(len(banks)))
    if rng.random() < 0.3:
        order = order[:-1]
    for k in order:
        text = noisify(fill(pick(banks[k], rng, split), rng), rng, noise)
        turns.append({"speaker": "caller", "text": text, "tactics": []})
        turns.append({"speaker": "receiver", "text": rng.choice(RECEIVER["genuine"] + RECEIVER["neutral"]),
                      "tactics": []})
    return {"id": f"{split}-{type_id}-{idx}", "label": 0, "type": type_id, "style": "genuine",
            "split": split, "source": "gen", "turns": turns, "first_suspicious_caller_turn": None}


def generate(split, n_scam, n_genuine, seed):
    rng = random.Random(seed)
    calls = []
    scam_types = list(SCAMS)
    gen_types = list(GENUINE)
    for i in range(n_scam):
        calls.append(make_scam(scam_types[i % len(scam_types)], rng, split, i))
    for i in range(n_genuine):
        calls.append(make_genuine(gen_types[i % len(gen_types)], rng, split, i))
    rng.shuffle(calls)
    return calls


def main():
    out = Path(sys.argv[1] if len(sys.argv) > 1 else "../data-eval/generated")
    out.mkdir(parents=True, exist_ok=True)
    plan = {"train": (2000, 2000, 1), "dev": (300, 300, 2), "test": (300, 300, 3)}
    for split, (ns, ng, seed) in plan.items():
        calls = generate(split, ns, ng, seed)
        with open(out / f"{split}.jsonl", "w", encoding="utf-8") as f:
            for c in calls:
                f.write(json.dumps(c, ensure_ascii=False) + "\n")
        print(split, len(calls))


if __name__ == "__main__":
    main()
