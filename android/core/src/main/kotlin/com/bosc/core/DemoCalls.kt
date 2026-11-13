package com.bosc.core

/**
 * Scripted calls for simulation mode, the speaker demo and the self-test.
 *
 * Written by hand for the demo. They are NOT the lockbox test set and must never be used as
 * headline numbers (plan section 1): the person who wrote the rules also wrote these calls.
 *
 * Turn indexes are 0-based over all turns (caller and you).
 */
data class DemoCall(
    val id: String,
    val title: String,
    /** S1..S10 for scams, G1..G8 for genuine types. */
    val typeId: String,
    val isScam: Boolean,
    val language: String,
    val turns: List<Pair<Speaker, String>>,
    /** Gold: first caller turn at which a careful person should become suspicious (-1 for genuine). */
    val firstSuspiciousTurn: Int = -1,
    /** Gold: turn where the caller asks for the payment, OTP or app install that does the damage (-1 for genuine). */
    val paymentTurn: Int = -1,
    /** Red-team call built to beat keyword rules (plan 6.3). Reported separately. */
    val redTeam: Boolean = false,
    /** What the caller phone shows in the two-phone demo. */
    val callerName: String = "Unknown number",
)

private fun c(s: String) = Speaker.CALLER to s
private fun y(s: String) = Speaker.YOU to s

object DemoCalls {

    val all: List<DemoCall> = listOf(
        DemoCall(
            "scam_digital_arrest_en", "Digital arrest (fake customs + CBI)", "S2", true, "English",
            listOf(
                c("Hello, am I speaking with Mr. Ramesh Kumar?"),
                y("Yes, speaking. Who is this?"),
                c("Sir, I am calling from FedEx courier. A parcel booked on your Aadhaar number has been stopped by customs in Mumbai."),
                y("A parcel? I haven't sent any parcel."),
                c("Sir, the parcel has five passports and 200 grams of MDMA drugs. I am connecting you to the Mumbai cyber crime branch."),
                c("This is Inspector Vikram Singh, cyber crime. An FIR has been registered against you for money laundering. There is an arrest warrant in your name."),
                y("What? This must be a mistake, I am a retired teacher."),
                c("Then you must cooperate with the investigation. This is a confidential matter of national security. Do not tell anyone, not even your family."),
                c("Do not disconnect this call. Stay on the line, you are under digital surveillance. Join the video call on Skype now."),
                c("To prove your innocence you have to transfer your savings to the RBI verification account immediately. It will be refunded after verification."),
                y("How much do I have to send?"),
                c("Send 4 lakh 80 thousand rupees right now to this account, otherwise the police will come to your house today itself."),
            ),
            firstSuspiciousTurn = 2, paymentTurn = 9, callerName = "Unknown · Mumbai",
        ),
        DemoCall(
            "scam_kyc_hinglish", "Fake bank KYC block (Hinglish)", "S1", true, "Hinglish",
            listOf(
                c("Hello sir, main SBI head office se bol raha hoon, KYC department."),
                y("Haan ji, boliye."),
                c("Sir aapka KYC update pending hai, aaj raat 12 baje tak aapka account block ho jayega."),
                y("Arre, par maine toh pichhle saal update kiya tha."),
                c("Sir system mein nahi dikh raha. Jaldi karna padega, warna saari transactions band ho jayengi."),
                c("Aapke mobile pe ek OTP aaya hoga, woh code jo aapko aaya hai, mujhe bata dijiye, main abhi update kar deta hoon."),
                y("Ek minute, dekhta hoon."),
                c("Sir jaldi bataiye, call mat kaatna, time khatam ho raha hai."),
            ),
            firstSuspiciousTurn = 2, paymentTurn = 5,
        ),
        DemoCall(
            "scam_remote_refund_en", "Refund via screen-sharing app", "S3", true, "English",
            listOf(
                c("Good afternoon ma'am, this is from Flipkart customer care regarding your refund of 2,499 rupees."),
                y("Oh yes, I had returned a mixer last week."),
                c("Your refund has failed due to a technical problem. To process it I need to guide you on your phone."),
                c("Please go to Play Store and install the AnyDesk app. It is our official support app."),
                y("Okay, it is installing. It is asking for permissions."),
                c("Allow all permissions and tell me the 9 digit code shown on the screen."),
                c("Now open your Google Pay. Enter 2499 and your UPI PIN, the amount will come into your account."),
                y("But this says pay, not receive?"),
                c("Ma'am that is how the refund works, it is a collect request. Just approve the request quickly, my supervisor is waiting."),
            ),
            firstSuspiciousTurn = 3, paymentTurn = 6, callerName = "Unknown · Bengaluru",
        ),
        DemoCall(
            "scam_electricity_hinglish", "Electricity disconnection (Hinglish)", "S4", true, "Hinglish",
            listOf(
                c("Namaste, main electricity board office se bol raha hoon."),
                c("Aapka pichhle mahine ka bill update nahi hua hai. Aaj raat 9:30 baje aapki light kaat di jayegi."),
                y("Par maine toh bill bhar diya tha online."),
                c("Haan madam, system mein nahi aaya. Turant 10 rupaye ka verification payment kijiye."),
                c("Main aapko ek link bhej raha hoon, us par click karke app download kar lo aur payment kar do."),
                y("Theek hai, link bhejiye."),
            ),
            firstSuspiciousTurn = 1, paymentTurn = 3,
        ),
        DemoCall(
            "scam_family_emergency_en", "Fake relative in trouble", "S10", true, "English",
            listOf(
                c("Mummy, it's me, I'm calling from my friend's phone. My phone broke."),
                y("Beta? Your voice sounds different. Is everything okay?"),
                c("I had an accident with the bike. The other person is in hospital and the police are saying they will file a case."),
                c("I need 40,000 rupees urgently to settle it right now. Please don't tell papa, he will get very angry."),
                y("Oh god. Where should I send it?"),
                c("Send it on GPay to this number, it is the lawyer's UPI ID. Please hurry, don't cut the call."),
            ),
            firstSuspiciousTurn = 2, paymentTurn = 5, callerName = "Unknown · Delhi",
        ),
        DemoCall(
            "scam_polite_kyc_en", "Polite, slow bank scam (red-team style)", "S1", true, "English",
            listOf(
                c("Good morning sir, I'm Priya from HDFC Bank. Hope you're doing well."),
                y("Morning. Yes, what is it about?"),
                c("Nothing to worry sir, we're upgrading your credit card to a lifetime-free platinum card."),
                y("Oh, okay. Is there any charge?"),
                c("No charge at all sir. For the upgrade I just need to confirm the card number and expiry date on the card."),
                c("You will also get a one time password on your phone, kindly read it out to complete the upgrade."),
            ),
            firstSuspiciousTurn = 4, paymentTurn = 5, redTeam = true, callerName = "Unknown · Hyderabad",
        ),
        DemoCall(
            "scam_digital_arrest_hi", "डिजिटल अरेस्ट (Hindi)", "S2", true, "Hindi",
            listOf(
                c("नमस्ते, मैं सीबीआई ऑफिस से इंस्पेक्टर शर्मा बोल रहा हूं।"),
                y("जी, क्या बात है?"),
                c("आपके आधार नंबर से एक बैंक खाता खोला गया है जिसमें मनी लॉन्ड्रिंग हुई है। आपके नाम पर गिरफ्तार करने का वारंट है।"),
                c("यह बात किसी को मत बताना। फोन मत काटना।"),
                c("अपनी सारी रकम जांच के लिए सुरक्षित खाते में तुरंत पैसे ट्रांसफर करो।"),
            ),
            firstSuspiciousTurn = 0, paymentTurn = 4,
        ),
        DemoCall(
            "scam_sim_tanglish", "SIM block (Tamil-English)", "S5", true, "Tamil-English",
            listOf(
                c("Hello sir, naan TRAI department la irundhu pesuren."),
                c("Ungal number illegal activity ku use aagudhu, two hours la ungal SIM block aagidum."),
                y("Enna sir, naan onnum pannala."),
                c("Seekiram verification pannanum. Ungalukku vandha OTP code sollunga, yaarukkum sollatheenga."),
            ),
            firstSuspiciousTurn = 1, paymentTurn = 3,
        ),

        // ---------------- Genuine calls, mostly hard negatives ----------------
        DemoCall(
            "genuine_delivery_otp_en", "Delivery agent asks for the delivery OTP", "G1", false, "English",
            listOf(
                c("Hello sir, I'm calling from Amazon delivery. Your parcel is out for delivery and I'm outside your building."),
                y("Okay, I'm coming down in two minutes."),
                c("Sure sir. Please keep the delivery OTP ready, the one you got in the SMS from Amazon."),
                y("It is 4 8 2 1."),
                c("Thank you sir, order delivered. Have a nice day."),
            ),
            callerName = "Amazon Delivery",
        ),
        DemoCall(
            "genuine_delivery_otp", "Delivery agent asks for delivery OTP (Hinglish)", "G1", false, "Hinglish",
            listOf(
                c("Hello, Amazon delivery se bol raha hoon. Aapka parcel aaya hai."),
                y("Haan, main aa raha hoon neeche."),
                c("Sir main gate pe khada hoon. Delivery ke liye OTP bata dijiye jo SMS mein aaya hai."),
                y("Haan, 4 8 2 1."),
                c("Theek hai sir, order delivered. Thank you."),
            ),
        ),
        DemoCall(
            "genuine_emi_reminder", "Bank EMI reminder", "G2", false, "English",
            listOf(
                c("Hello, am I speaking with Ms. Anjali? This is a reminder call from ICICI Bank."),
                y("Yes, speaking."),
                c("Your car loan EMI of 18,450 rupees is due on the 5th. Please keep sufficient balance for the auto-debit to avoid a late fee."),
                y("Okay, I'll make sure."),
                c("Thank you ma'am. If you have any questions, you can call the customer care number on our official website. Have a good day."),
            ),
        ),
        DemoCall(
            "genuine_bank_fraud_check", "Bank genuinely verifying a transaction", "G3", false, "English",
            listOf(
                c("Hello, this is the fraud monitoring team from HDFC Bank. We noticed a transaction of 45,000 rupees at an electronics store just now."),
                y("No, I didn't make any purchase!"),
                c("Thank you, we have declined it. Please remember the bank will never ask for your OTP or PIN on a call."),
                c("A replacement card will be sent to your address. You can also check this on the official app or call the number on the back of your card."),
                y("Okay, thank you so much."),
            ),
        ),
        DemoCall(
            "genuine_family_money", "Son asks for college fees", "G5", false, "Hinglish",
            listOf(
                c("Papa, kaise ho? Ghar pe sab theek?"),
                y("Haan beta, sab theek. Tum batao, padhai kaisi chal rahi hai?"),
                c("Achhi chal rahi hai. Papa, semester ki fees 15 tareekh tak jama karni hai, 22,000 rupees."),
                y("Theek hai, main kal tak bhej dunga."),
                c("Thank you papa. Mummy ko bolna main Sunday ko call karunga."),
            ),
        ),
        DemoCall(
            "genuine_police_verification", "Real police address verification", "G4", false, "English",
            listOf(
                c("Good morning, this is constable Rajesh from Adyar police station, for your passport verification."),
                y("Oh yes, I applied last month."),
                c("I will come to your house tomorrow at 11 for the address check. Please keep your Aadhaar and the original documents ready."),
                y("Sure, I'll be home."),
                c("Thank you. There is no fee for this, so do not pay anyone. See you tomorrow."),
            ),
        ),
        DemoCall(
            "genuine_scam_aware", "Friend warns about scams", "G8", false, "English",
            listOf(
                c("Hey, did you hear what happened to my uncle? Someone called saying they were from CBI and kept him on the line for hours."),
                y("What! Did he pay?"),
                c("Almost. They told him not to tell anyone and to transfer money to a safe account. Luckily my aunt walked in."),
                y("That's scary. I heard about these scams on the news."),
                c("Yeah, just remember the real police never ask you to pay on a call. If anyone calls like that, just hang up and tell me."),
            ),
        ),
        DemoCall(
            "genuine_telemarketing", "Pushy loan telemarketing", "G6", false, "Hinglish",
            listOf(
                c("Hello sir, main Bajaj Finance se bol raha hoon. Aapke liye pre-approved personal loan offer hai, 5 lakh tak."),
                y("Mujhe abhi loan nahi chahiye."),
                c("Sir offer sirf is mahine tak hai, interest bahut kam hai. Aap app pe check kar sakte ho."),
                y("Nahi, thank you."),
                c("Theek hai sir, koi baat nahi. Dhanyavaad."),
            ),
        ),
        DemoCall(
            "genuine_plumber", "Plumber running late", "G7", false, "English",
            listOf(
                c("Hello madam, plumber Suresh here. I'm running 20 minutes late, there's traffic."),
                y("Okay, but please come fast, the kitchen is flooding."),
                c("Yes madam, close the main valve near the meter right now. I'll be there soon. Payment you can give after the work."),
            ),
        ),
    )

    fun byId(id: String) = all.first { it.id == id }
}
