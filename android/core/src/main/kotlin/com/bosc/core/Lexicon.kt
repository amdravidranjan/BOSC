package com.bosc.core

/**
 * Pattern lexicon for the Stage 0 rule scorer (ablation row "A. Keyword rules").
 *
 * Covers English, romanised Hindi (Hinglish), Devanagari Hindi and romanised Tamil-English.
 * Patterns are matched against lower-cased text. Each pattern carries a strength in [0, 1];
 * several hits for the same tactic combine as a noisy-OR.
 *
 * This is deliberately written from public advisory patterns (RBI, I4C, bank awareness pages),
 * not copied from any script. It is the baseline the learned models must beat.
 */
internal class Pat(val regex: Regex, val weight: Float)

private fun p(weight: Float, vararg patterns: String): List<Pat> =
    patterns.map { Pat(Regex(it, RegexOption.IGNORE_CASE), weight) }

internal object Lexicon {

    val tactics: Map<Tactic, List<Pat>> = mapOf(
        Tactic.URGENCY to
            p(0.8f,
                """\bimmediately\b""", """\bright (now|away)\b""", """\burgent(ly)?\b""",
                """\bwithin (the )?(next )?(\d+|one|two|five|ten|fifteen|thirty|half an?) ?(minutes?|mins?|hours?|hrs?)\b""",
                """\blast (chance|warning|opportunity)\b""", """\btoday itself\b""",
                """\bbefore (the )?(end of (the )?day|evening|5|five)\b""", """\bno time\b""", """\bhurry\b""",
                """\bas soon as possible\b""", """\basap\b""", """\babhi ke abhi\b""", """\bturant\b""",
                """\bjaldi\b""", """\baaj hi\b""", """\bf(a|ou)u?ran\b""", """\budane\b""",
                """\bs(ee|i)kk?(i)?ram\b""", """உடனே""", """இப்போதே""", """तुरंत""", """जल्दी""", """फौरन""",
                """\btime (is )?(running out|khatam)\b""", """\bdeadline\b""",
            ) + p(0.35f, """\babhi\b""", """\bquickly\b""", """\bfast\b""", """\btoday\b""", """अभी"""),

        Tactic.THREAT to
            p(0.85f,
                """\barrest(ed|ing)?\b""", """\bwarrant\b""", """\bjail\b""", """\bprison\b""", """\bfir\b""",
                """\bcase (has been |is |was )?(registered|filed|booked|lodged)\b""", """\blegal action\b""",
                """\bmoney laundering\b""", """\b(drugs?|narcotics|mdma)\b""", """\billegal\b""", """\bterror""",
                """\b(account|card|sim|number|aadhaa?r|connection|electricity|power|pan)\b.{0,25}\b(blocked|frozen|freeze|suspended|deactivated|disconnected|cancell?ed|seized)\b""",
                """\b(block|freeze|suspend|disconnect|deactivate|seize)(d|ed)? (your|the) (account|card|sim|number|aadhaa?r|connection|bank account)\b""",
                """\bgiraft?aa?r""", """\bkaid\b""", """\bband (ho ja(y|e)ega|kar (diya jayega|denge|denge))\b""",
                """\bblock (ho ja(y|e)ega|kar denge|ho jaayega)\b""", """\b(block|cut|katt?) aagidum\b""",
                """\bpolice (will )?(come|reach|aayegi|aa jayegi)\b""", """गिरफ्तार""", """जेल""", """ब्लॉक""",
                """बंद (हो|कर)""", """\bdigital arrest\b""",
                """\b(light|bijli|connection|line|power|current|net) (kaat|kat|cut)( di| kar)?( ja(y|e)egi| ja(y|e)ega| denge| doonga| hogi)?\b""",
                """\b(kaat|kat) (di|diya) ja(y|e)e?g(i|a)\b""", """\bcourt case\b""", """\bsummons\b""",
            ) + p(0.45f,
                """\bpenalty\b""", """\blate fee\b""", """\bfine\b""", """\bsuspicious (activity|transaction)\b""",
                """\bproblem with your (account|card|sim)\b""", """\baccident\b""", """\bhospital\b""",
                """\bin (big |serious )?trouble\b""", """\bmusibat\b""", """\bblocked\b""",
            ),

        Tactic.SECRECY_ISOLATION to
            p(0.9f,
                """\b(don'?t|do not|dont|never) (tell|inform|discuss|mention)( this| it| about this)?( to| with)? (anyone|anybody|your family|family|your (wife|husband|son|daughter|children|kids|parents|mom|dad)|mom|dad|bank|police|relatives|neighbou?rs)\b""",
                """\bkeep (this|it) (a )?(secret|confidential|between us)\b""", """\bconfidential (matter|investigation|case|enquiry|inquiry)\b""",
                """\bnational security\b""", """\bsecret (investigation|operation|enquiry)\b""",
                """\bkisi ?(ko|se)( bhi)? (mat|na|nahi) (batana|bataye|bataiye|bataana|bolna|kehna)\b""",
                """\b(ghar|family|mummy|papa|mom|dad|wife|husband|beta|bete)( (walon|waalon|wale|waale))? ko (mat|na|nahi) (batana|bataana|bataiye|bolna)\b""",
                """\bakele\b.{0,20}\b(room|kamre)\b""", """\b(go|move) (in)?to (a|an|another|your)? ?(private|separate|closed|empty|different)? ?room\b""",
                """\block (the|your) (door|room)\b""", """\balone in (a|the|your) room\b""",
                """\byaar(ukk?um|kittayum|kitteyum) (sollatheenga|sollaadheenga|solla vendam|sollaadhe)\b""",
                """किसी को (मत|न) (बताना|बताएं|बताइए)""", """गोपनीय""",
            ),

        Tactic.AUTHORITY_CLAIM to
            p(0.85f,
                """\bcbi\b""", """\bcentral bureau\b""", """\benforcement directorate\b""", """\bed officer\b""",
                """\bnarcotics (control )?bureau\b""", """\bncb\b""", """\bcustoms\b""", """\bcyber ?(crime|cell|police)\b""",
                """\bcrime branch\b""", """\b(police|inspector|sub[- ]inspector|dcp|sho|constable|commissioner)\b""",
                """\brbi\b""", """\breserve bank\b""", """\btrai\b""", """\btelecom (regulatory|department)\b""",
                """\bincome tax (department|officer|office)\b""", """\buidai\b""",
                """\b(supreme|high) court\b""", """\bcourt (order|notice|summons)\b""", """\bjudge\b""", """\bmagistrate\b""",
                """\bgovernment (of india|department|officer|official)\b""", """\bministry\b""",
                """पुलिस""", """सीबीआई""", """\bkasta?ms?\b""",
            ) + p(0.6f,
                """\b(i am|i'm|this is|main|mai|naan)( an?)? (officer|inspector|official)\b""", """\bhead ?office\b""",
                """\bheadquarters\b""", """\b(fedex|dhl|blue ?dart)\b""", """\bkyc (department|team|update|verification)\b""",
                """\b(electricity|power) (board|department|office)\b""", """\b(bescom|tneb|tangedco|msedcl)\b""",
                """\baadhaa?r (department|office|centre|center)\b""",
            ) + p(0.45f,
                """\b(sbi|hdfc|icici|axis|kotak|pnb|canara|bank of baroda|state bank)\b""",
                """\bbank (se|from|ki taraf se)\b""", """\bcustomer (care|support|service)\b""", """\bmanager\b""",
            ),

        Tactic.CREDENTIAL_REQUEST to
            p(0.9f,
                """\botp\b""", """\bo\.? ?t\.? ?p\b""", """\bone[- ]time password\b""", """ओटीपी""", """\bcvv\b""",
                """\b(upi|atm|card|m-?pin|secret) ?pin\b""", """\bpin\b(?! ?code)""", """\bpassword\b""",
                """\bcard (number|details|no)\b""", """\bexpiry( date)?\b""",
                """\b(verification|security|confirmation) code\b""", """\bnet ?banking (id|password|login|details)\b""",
                """\blogin (details|id|credentials)\b""",
                """\bcode (that|which|jo) (you|aapko|apko|aaya|came|received|has come)\b""",
                """\b(read|tell|share|give|batao|bataiye|bata do|bolo|sollunga)( me| us)?( the| that| your)? (code|otp|pin)\b""",
            ) + p(0.5f,
                """\b(aadhaa?r|pan) (number|details|card details)\b""", """\baccount (number|details)\b""",
                """\bdate of birth\b""", """\bmother'?s maiden\b""",
            ),

        Tactic.REMOTE_ACCESS to
            p(0.95f,
                """\bany ?desk\b""", """\bteam ?viewer\b""", """\bquick ?support\b""", """\brust ?desk\b""",
                """\bair ?droid\b""", """\bammyy\b""", """\bscreen ?(share|sharing|mirror|mirroring)\b""",
                """\bremote (access|control|app|support app)\b""", """\bapk\b""",
            ) + p(0.6f,
                """\b(install|download)( this| the| an| one| a| that)? (app|application)\b""",
                """\bapp (download|install) (karo|kariye|kijiye|karein|kar lo|pannunga|pannunga)\b""",
                """\bplay ?store (se|par|pe|mein|la)\b""", """\bclick (on )?(the |this |that )?link\b""",
                """\blink (bhej|send|sent|par click|pe click)""", """\b(share|open) (your )?screen\b""",
            ),

        Tactic.PAYMENT_INSTRUCTION to
            p(0.85f,
                """\btransfer( the| all| your| this)?( amount| money| funds| balance| savings| rs| ₹| rupees| \d)""",
                """\bsend( the| me| us| this)?( money| amount| rs\.?| ₹| rupees| \d)""",
                """\bpay( the| a| this)? (fine|fee|fees|amount|charges|penalty|deposit|\d+|rs|₹)""",
                """\b(safe|secure|secured|rbi|government|verification|escrow|suspense|nodal) account\b""",
                """\bupi id\b""", """\bscan (the |this |that )?qr\b""", """\bqr code\b""",
                """\b(collect|payment) request\b""", """\b(approve|accept) (the |this )?request\b""",
                """\b(refundable|security|verification|processing|clearance|registration|release) (amount|fee|deposit|charge|charges)\b""",
                """\bpaise? (bhej|transfer|daal|dalo|jama)""", """\bpaisa (bhej|transfer)""",
                """\bamount (bhej|transfer|jama|daal)""",
                """\b(verification|processing|registration|update|pending|small|token) payment\b""",
                """\bpayment (kijiye|karo|kar do|kariye|kar dijiye|pannunga|panni)\b""",
                """\b(gpay|google pay|phone ?pe|paytm|bhim)( (karo|kar do|par|pe|me|mein|open|kariye|pannunga))""",
                """पैसे (भेज|ट्रांसफर|जमा)""", """\bpanam (anuppu|anupunga|anuppunga|transfer)""",
                """\b(put|move|deposit) (all )?(your |the )?(money|savings|funds)\b""",
            ) + p(0.45f,
                """(₹|\brs\.? ?|\brupees )\d""", """\b\d+[\d,]* ?(rupees|rupaye|rupay|rupiya|rs|lakh|lakhs|thousand|hazaa?r|crore)\b""",
                """\bpay\b""", """\bpayment\b""", """\b(gpay|google pay|phone ?pe|paytm)\b""",
            ),

        Tactic.STAY_ON_LINE to
            p(0.9f,
                """\b(don'?t|do not|dont|never) (cut|disconnect|hang up|end|drop|put down)( the| this| your)?( call| phone| line)?\b""",
                """\bstay on (the )?(line|call|phone)\b""", """\bremain on (the )?(line|call)\b""",
                """\b(keep|remain)( the| this| your)? (call|phone|line|video|camera) (on|connected|active|running)\b""",
                """\bcall (mat|na) (kaat|kat|cut)""", """\bphone (mat|na) (kaat|kat|rakh|rakhna|band)""",
                """\bline (par|pe) (rahiye|rahe|raho|rahna)\b""", """\bskype\b""",
                """\b(stay|remain) (connected|online)\b""", """\bcall cut (mat|pannatheenga|pannaadheenga)\b""",
                """फोन (मत|न) (काट|रख)""", """\bunder (digital )?(surveillance|observation|monitoring)\b""",
            ) + p(0.6f, """\bvideo call\b""", """\bcamera (on|chalu)\b"""),
    )

    /** Phrases that point to a genuine call. They lower risk; they never raise it. */
    val genuineCues: List<Pat> =
        p(0.9f,
            """\b(never|don'?t|do not|dont|will not|won'?t)( ever)? (share|ask( you)?( for)?|tell|give)( your| the| this| anyone| it)?( otp| pin| password| cvv)\b""",
            """\b(bank|we|rbi|police)( will)? never (ask|call|request)\b""",
            """\botp (kisi ke saath|kisi ko) (share )?(mat|na)\b""",
        ) + p(0.75f,
            """\b(call|contact|visit)( us| the bank| them)?( back)? (on|at|through|using) (the )?(official|number (on|behind|printed on) (your|the) card|customer care number|website)\b""",
            """\b(is|sounds like|this is) (a |definitely a )?(scam|fraud)\b""", """\bheard about (these|this|such) (scams?|frauds?)\b""",
            """\b(do not|don'?t) fall for\b""", """\bofficial (app|website)\b""", """\bverify (it )?(yourself|on the app|in the app)\b""",
            """\bi will call (you )?back\b""", """\bmain (khud|baad mein) call\b""",
        ) + p(0.6f,
            """\b(visit|go to|come to) (your|the)( nearest| home)? branch\b""",
            """\b(emi|bill|premium|instal?lment)( is| amount)?( due| pending)\b""", """\bdue date\b""", """\bauto[- ]?debit\b""",
            """\bno need to (pay|share|transfer)\b""", """\byou (don'?t|do not) (have|need) to (pay|share)\b""",
        ) + p(0.3f, """\bappointment\b""", """\bparent[- ]teacher\b""", """\bplumber\b""", """\bclinic\b""")

    /** Delivery context. An OTP asked for in this context is usually the genuine delivery OTP. */
    val deliveryContext: List<Pat> = p(1f,
        """\bdelivery (boy|agent|partner|executive|guy|wala)\b""", """\bout for delivery\b""",
        """\b(at|outside|near) your (door|gate|building|house|flat)\b""", """\b(gate|door|neeche|bahar) (pe|par|par hi)? ?(hoon|hu|khada|wait)\b""",
        """\border (has been )?(delivered|arrived)\b""", """\bcash on delivery\b""", """\bcod\b""",
        """\b(swiggy|zomato|amazon|flipkart|blinkit|zepto|bigbasket|myntra|meesho|dunzo)\b""",
        """\byour (order|package|parcel) (is )?(here|arrived|outside|downstairs)\b""", """\bdeliver(y|ed)\b""",
    )

    /** Phrases that cancel a nearby genuine cue (a scammer saying "don't share the OTP with anyone except me"). */
    val cueCancel: List<Pat> = p(1f, """\bexcept (me|us)\b""", """\bonly (to |with )?(me|us)\b""", """\bsirf (mujhe|humein)\b""")
}
