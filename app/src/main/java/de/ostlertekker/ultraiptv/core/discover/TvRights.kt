package de.ostlertekker.ultraiptv.core.discover

/**
 * Übertragungsrechte großer Wettbewerbe nach Land (Rechte-Tabelle).
 * Quellen: Wikipedia-Listen der Rechteinhaber (Champions League 2024–27, Premier League 2025–28),
 * formula1.com (Formel 1 2026) sowie Ankündigungen der Ligen. Stand: Saison 2025/26.
 * Rechte wechseln je nach Saison – die Tabelle ist eine Ergänzung zum TV-Programm-Scan, nicht die einzige Quelle.
 *
 * Format pro Wettbewerb: "LAND=Sender A,Sender B;LAND2=…". Ländercodes siehe [ChannelCountry].
 */
object TvRights {
    private val raw: Map<League, String> = mapOf(
        League.CHAMPIONS to
            "AL=Tring,Vizion Plus;AM=Fast Sports;AU=Stan Sport;AT=Sky Sport,Canal+;AZ=CBC Sport,ITV;BY=Okko;BE=RTL,VTM,Proximus,Play Sports;" +
            "BA=Arena Sport;BR=TNT Sports,SBT;BG=bTV,Max Sport;CA=DAZN;CN=iQIYI;HR=HRT,Arena Sport;CY=CYTA;CZ=Nova Sport;DK=Viaplay;" +
            "EE=Go3 Sport;FI=MTV;FR=Canal+,M6;GE=Setanta Sports,Silk Sport;DE=DAZN,Prime Video,ZDF;GR=MEGA,Cosmote Sport;HK=beIN Sports;" +
            "HU=RTL,Sport1;IS=Syn,Viaplay;IN=Sony Sports,Sony Ten;ID=beIN Sports;IE=RTE,Premier Sports,Virgin Media,TNT Sports,Prime Video;" +
            "IL=Sports Channel;IT=Sky Sport,Prime Video;JP=WOWOW;KZ=Qazsport,Q Sport;XK=RTK,ArtMotion;LV=Go3 Sport;LT=Go3 Sport;LU=RTL,Proximus;" +
            "MY=beIN Sports;MT=PBS,TSN;MENA=beIN Sports;MX=Fox,TNT Sports;MD=Setanta Sports;ME=Arena Sport;NL=Ziggo Sport;NZ=DAZN;MK=Arena Sport;" +
            "NO=TV2;PK=tapmad;PL=TVP,Canal+;PH=beIN Sports;PT=DAZN,Sport TV;RO=Digi Sport,Prima Sport;RS=RTS,Arena Sport;SG=beIN Sports;" +
            "SK=Nova Sport;SI=Pro Plus,Sport Klub;LATAM=ESPN;KR=SPOTV;ES=Movistar;AFRICA=SuperSport,Canal+;SE=Viaplay;CH=SRF,RTS,RSI,Blue Sport;" +
            "TW=ELTA;TH=beIN Sports;TR=TRT;UA=Megogo;UK=TNT Sports,Prime Video,BBC;US=CBS Sports,Paramount+,TUDN,Univision,DAZN;VN=VTVcab",
        League.PREMIER to
            "AFRICA=SuperSport,Canal+;CN=Migu,CCTV;HK=now TV,Now Sports;IN=Star Sports;ID=Emtek;IR=IRIB Varzesh;JP=U-Next;MY=Astro;" +
            "KR=Coupang Play;SG=StarHub;TW=ELTA;TH=Monomax;VN=FPT;AL=SuperSport;AM=Fast Sports;AT=Sky Sport;AZ=Setanta Sports,Idman TV;" +
            "BE=Play Sports;BA=Arena Sport;HR=Arena Sport;CY=Cytavision;CZ=Canal+ Sport;DK=Viaplay,Prime Video;EE=Go3 Sport;FI=Viaplay;" +
            "FR=Canal+;GE=Setanta Sports;DE=Sky Sport;GR=Nova Sports;HU=Spiler,Arena4;IS=Syn Sport;IE=Premier Sports,Sky Sports,TNT Sports;" +
            "IT=Sky Sport;LV=Go3 Sport;LT=Go3 Sport;LU=Sky Sport,Play Sports;MT=TSN;MD=Setanta Sports;ME=Arena Sport;NL=Viaplay,Prime Video;" +
            "MK=Arena Sport;NO=Viaplay;PL=Canal+;PT=DAZN;RO=Voyo;RS=Arena Sport;SK=Canal+ Sport;SI=Arena Sport;ES=DAZN,Movistar;" +
            "SE=Viaplay,Prime Video;CH=Sky Sport,Canal+;TR=beIN Sports;UA=Setanta Sports;BR=ESPN,CazeTV;LATAM=ESPN;MX=Fox,TNT Sports;" +
            "IL=Sport 1;MENA=beIN Sports;CA=fuboTV;US=NBC,Peacock,Telemundo,USA Network;AU=Stan Sport;NZ=Sky Sport;UK=Sky Sports,TNT Sports",
        League.F1 to
            "AFRICA=SuperSport;AL=DigitAlb;AM=FastSports;AU=Fox Sports,Foxtel,Kayo;AT=ServusTV,ORF,Sky;AZ=Idman;BE=RTBF,Play Sports;" +
            "BA=Arena Sport;BR=Globo,sportv;BG=Nova;CA=RDS,TSN,Noovo;CN=Shanghai TV,Tencent;TW=Videoland,ELTA;HR=RTL;CY=Omega;" +
            "CZ=Nova;SK=Nova;DK=TV3,Viaplay;EE=Go3,TV3;FI=Viaplay;FR=Canal+;DE=Sky Sport,RTL;GR=ANT1;HK=beIN Sports,Now Sports;HU=M4;" +
            "IS=Viaplay;IN=FanCode;ID=beIN Sports;IL=Sport 5;IT=Sky Sport,Sky Italia;JP=Fuji TV;XK=Arena Sport,Artmotion;LATAM=ESPN;" +
            "LV=Go3,TV3;LT=Go3,TV3;LU=RTL;MK=Sport Klub;MY=beIN Sports;MT=PBS;MENA=beIN Sports;MX=TUDN,Sky Sports,Izzi;ME=Arena Sport;" +
            "NL=Viaplay;NZ=Sky Sport;NO=V sport,Viaplay;PH=beIN Sports;PL=Eleven Sports;PT=DAZN;RO=Antena;RS=Arena Sport;SG=beIN Sports;" +
            "SI=Kanal A,Voyo;KR=Coupang Play;ES=DAZN;SE=Viaplay;CH=SRF,RSI,RTS,Sky Sport;TH=beIN Sports;TR=beIN Sports;UA=Setanta;" +
            "UK=Sky Sports,Channel 4;IE=Sky Sports,Channel 4;US=Apple TV;VN=TV360",
        League.BUNDESLIGA to
            "DE=Sky Sport,DAZN,Sat.1;AT=Sky Sport;CH=Sky Sport;UK=Sky Sports,BBC,Prime Video;IE=Sky Sports;US=ESPN;LATAM=ESPN;" +
            "CA=DAZN;NL=Viaplay;DK=Viaplay;SE=Viaplay;NO=Viaplay;FI=Viaplay;IS=Viaplay;EE=Viaplay;FR=beIN Sports;IT=Sky Sport;" +
            "ES=Movistar;TR=beIN Sports,Tivibu Spor;GR=Nova Sports;HU=Arena4,Spiler;SI=Sport Klub;UA=Setanta Sports;GE=Setanta Sports;" +
            "AM=Setanta Sports,Fast Sports;AZ=Setanta Sports;AL=SuperSport;IL=Sport 1;MENA=beIN Sports;AU=beIN Sports;CN=Migu,CCTV;AFRICA=Canal+,StarTimes",
        League.BUNDESLIGA2 to "DE=Sky Sport;AT=Sky Sport;CH=Sky Sport",
        League.DFB_POKAL to "DE=Sky Sport,ARD,Das Erste,ZDF;AT=Sky Sport;CH=Sky Sport",
        League.EUROPA to "DE=RTL,Nitro,RTL+,DAZN;UK=TNT Sports;IE=TNT Sports;MENA=beIN Sports;FR=Canal+;IT=Sky Sport;ES=Movistar;US=CBS Sports,Paramount+",
        League.LALIGA to
            "ES=Movistar,DAZN;DE=DAZN;AT=DAZN;IT=DAZN;PT=DAZN;BE=DAZN;JP=DAZN;UK=Premier Sports;IE=Premier Sports;US=ESPN;LATAM=ESPN,DSports;" +
            "CA=TSN;MENA=beIN Sports;FR=beIN Sports;AU=beIN Sports;MY=beIN Sports,sooka;TR=S Sport;AZ=Idman TV;AL=DigitAlb;CY=Cytavision,Novasports;" +
            "NL=Ziggo Sport;PL=Eleven Sports;AFRICA=SuperSport",
        League.SERIE_A to
            "IT=Sky Sport,DAZN;DE=DAZN;AT=DAZN;UK=DAZN;IE=DAZN;US=DAZN,Paramount+;FR=DAZN;MENA=Shasha,STARZPLAY;BR=CazeTV;LATAM=ESPN;" +
            "CA=TLN;PL=Eleven Sports;NL=Ziggo Sport;AL=SuperSport;AFRICA=SuperSport,Canal+;AU=beIN Sports",
        League.NATIONS to
            "DE=ARD,Das Erste,ZDF,RTL;AT=ORF,ServusTV;CH=SRF,RTS,RSI;UK=ITV,BBC,Sky Sports;FR=TF1,M6;IT=Rai;ES=La 1,TVE;PL=TVP;NL=NOS;" +
            "PT=RTP,SIC,TVI;BE=RTBF,VRT;TR=TRT;HR=HRT;RS=RTS;GR=ERT;DK=DR,TV2;SE=SVT,TV4;NO=NRK,TV2;CZ=CT;HU=M4",
        League.ATP to "DE=Sky Sport;AT=Sky Sport;CH=Sky Sport;UK=Sky Sports;IT=Sky Sport,SuperTennis;US=Tennis Channel;MENA=beIN Sports;FR=beIN Sports;ES=Movistar",
        League.WTA to "DE=Sky Sport,DAZN;UK=Sky Sports;US=Tennis Channel;MENA=beIN Sports;IT=SuperTennis",
        League.NBA to
            "US=ESPN,ABC,NBC,Peacock,Prime Video;UK=Sky Sports,Prime Video;DE=Prime Video;FR=beIN Sports;MENA=beIN Sports;CN=Tencent,Migu,CCTV;" +
            "LATAM=ESPN,Prime Video;BR=ESPN,Prime Video;IN=Prime Video;JP=WOWOW,Prime Video;HK=Now Sports;CA=TSN,Sportsnet",
    )

    private val parsed: Map<League, List<Pair<String, String>>> by lazy {
        raw.mapValues { (_, v) ->
            v.split(';').mapNotNull { e -> e.split('=', limit = 2).takeIf { it.size == 2 } }
                .flatMap { (c, list) -> list.split(',').map { c.trim() to it.trim() } }
                .filter { it.second.isNotBlank() }
        }
    }

    /** (Land, Sender) laut Rechte-Tabelle für einen Wettbewerb. */
    fun holders(l: League): List<Pair<String, String>> = parsed[l].orEmpty()
}

/** Erkennt Herkunftsland eines Senders aus Namens-Präfixen („DE:“, „|PL|“, „UK ▎“) und Gruppen („Polska“, „Deutschland“). */
object ChannelCountry {
    private val aliases: Map<String, List<String>> = mapOf(
        "DE" to listOf("de", "ger", "germany", "deutschland", "german", "deutsch"), "AT" to listOf("at", "austria", "osterreich"),
        "CH" to listOf("ch", "swiss", "schweiz", "switzerland", "suisse"), "UK" to listOf("uk", "gb", "england", "united kingdom", "british"),
        "IE" to listOf("ie", "ireland", "irish"), "PL" to listOf("pl", "polska", "poland", "polish"), "NL" to listOf("nl", "netherlands", "nederland", "holland", "dutch"),
        "BE" to listOf("be", "belgium", "belgie", "belgique"), "FR" to listOf("fr", "france", "french", "francais"), "IT" to listOf("it", "italy", "italia", "italian"),
        "ES" to listOf("es", "spain", "espana", "spanish"), "PT" to listOf("pt", "portugal", "portuguese"), "TR" to listOf("tr", "turkey", "turkiye", "turk", "turkish"),
        "US" to listOf("us", "usa", "united states"), "CA" to listOf("ca", "canada"), "MENA" to listOf("ar", "arab", "arabic", "mena", "arabia"),
        "GR" to listOf("gr", "greece", "greek"), "RO" to listOf("ro", "romania"), "HU" to listOf("hu", "hungary", "magyar"), "CZ" to listOf("cz", "czech", "cesko"),
        "SK" to listOf("sk", "slovakia"), "HR" to listOf("hr", "croatia", "hrvatska"), "RS" to listOf("rs", "serbia", "srbija"), "BA" to listOf("ba", "bosnia"),
        "SI" to listOf("si", "slovenia"), "BG" to listOf("bg", "bulgaria"), "DK" to listOf("dk", "denmark", "danmark"), "SE" to listOf("se", "sweden", "sverige"),
        "NO" to listOf("no", "norway", "norge"), "FI" to listOf("fi", "finland", "suomi"), "IS" to listOf("is", "iceland"), "EE" to listOf("ee", "estonia"),
        "LV" to listOf("lv", "latvia"), "LT" to listOf("lt", "lithuania"), "UA" to listOf("ua", "ukraine"), "AL" to listOf("al", "albania", "shqip"),
        "MK" to listOf("mk", "macedonia"), "XK" to listOf("xk", "kosovo"), "CY" to listOf("cy", "cyprus"), "MT" to listOf("mt", "malta"), "LU" to listOf("lu", "luxembourg"),
        "IL" to listOf("il", "israel"), "IN" to listOf("in", "india"), "PK" to listOf("pk", "pakistan"), "AU" to listOf("au", "australia"), "NZ" to listOf("nz", "new zealand"),
        "BR" to listOf("br", "brazil", "brasil"), "MX" to listOf("mx", "mexico"), "LATAM" to listOf("latam", "latino", "latin"), "AFRICA" to listOf("africa", "afr"),
        "JP" to listOf("jp", "japan"), "KR" to listOf("kr", "korea"), "CN" to listOf("cn", "china"), "HK" to listOf("hk", "hong kong"), "SG" to listOf("sg", "singapore"),
        "MY" to listOf("my", "malaysia"), "PH" to listOf("ph", "philippines"), "TH" to listOf("th", "thailand"), "VN" to listOf("vn", "vietnam"), "ID" to listOf("id", "indonesia"),
        "AM" to listOf("am", "armenia"), "AZ" to listOf("az", "azerbaijan"), "GE" to listOf("ge", "georgia"), "KZ" to listOf("kz", "kazakhstan"), "ME" to listOf("me", "montenegro"),
        "MD" to listOf("md", "moldova"), "BY" to listOf("by", "belarus"), "IR" to listOf("ir", "iran", "persian"), "TW" to listOf("tw", "taiwan"),
    )
    private val byAlias: Map<String, String> = aliases.flatMap { (c, l) -> l.map { it to c } }.toMap()

    /** Land aus Sendername bzw. Gruppe, sonst null. */
    fun detect(name: String, category: String): String? {
        prefix(name)?.let { return it }
        prefix(category)?.let { return it }
        val cat = TvMatch.norm(category)
        return aliases.entries.firstOrNull { (_, l) -> l.any { it.length >= 4 && cat.contains(" $it ") } }?.key
    }

    private fun prefix(s: String): String? {
        val m = Regex("""^\W*([A-Za-z]{2,7})\b""").find(s.trim()) ?: return null
        val tok = m.groupValues[1].lowercase()
        // Nur als Präfix werten, wenn ein Trennzeichen folgt („DE:“, „DE |“, „[PL]“, „UK ▎“)
        val after = s.trim().drop(m.range.last + 1).firstOrNull()
        return if (after != null && !after.isLetterOrDigit()) byAlias[tok] else null
    }

    /** Sendername ohne Länderpräfix und Qualitätskürzel, normalisiert („DE: Sky Sport Bundesliga 1 FHD“ → „sky sport bundesliga 1“). */
    fun cleanName(name: String): String {
        var s = name.trim()
        s = s.replace(Regex("""^\W*[A-Za-z]{2,7}\s*[:|▎\]\-]+\s*"""), "")
        s = s.replace(Regex("""\[[^\]]*]|\([^)]*\)"""), " ")
        var n = TvMatch.norm(s.replace("+", " plus "))
        n = n.replace(Regex("""\b(uhd|fhd|hd|sd|4k|8k|hevc|h265|h264|raw|backup|50fps|60fps|1080p|720p|vip|multi|audio|orig|hdr)\b"""), " ")
        return " " + n.replace(Regex("\\s+"), " ").trim() + " "
    }

    /** Land aus einem ausgeschriebenen Ländernamen („Germany“, „Poland“, „United Kingdom“). */
    fun fromCountryName(name: String): String? = byAlias[TvMatch.norm(name).trim()]

    /** Wörter, die hinter einem Sendernamen stehen dürfen, ohne dass es ein anderer Sender wird (Nummer, Sport-Zusatz, Land). */
    private val fillers = setOf("sport", "sports", "bundesliga", "premium", "top", "event", "extra", "live", "football", "fussball", "futbol", "calcio",
        "f1", "tennis", "plus", "max", "mix", "arena", "uhd", "fhd", "hd", "sd", "4k", "tv", "channel", "kanal", "ppv", "premier", "league", "liga", "action")

    /**
     * Strenger Abgleich: Der Sender muss mit dem Namen beginnen und darf danach nur Nummern/Sport-Zusätze/Land haben.
     * „RTL“ trifft „RTL“, aber nicht „RTL Nitro“, „RTL Crime“ oder „Super RTL“. Enthält [key] eine Nummer, muss sie übereinstimmen.
     */
    fun matches(clean: String, key: String): Boolean {
        if (key.isBlank() || !clean.startsWith(key)) return false
        val rest = clean.removePrefix(key).trim().split(' ').filter { it.isNotEmpty() }
        if (!rest.all { it.all(Char::isDigit) || it in fillers || it in byAlias }) return false
        val kn = Regex("""\d+""").findAll(key).map { it.value }.toList()
        val cn = Regex("""\d+""").findAll(clean).map { it.value }.toList()
        return kn.isEmpty() || kn == cn
    }

    /** Sendername aus einem TV-Plan („DAZN 1 Germany“, „Sky Sport Bundesliga 3 HD“) als Abgleich-Schlüssel ohne Land/Qualität. */
    fun listingKey(channel: String): String {
        val words = cleanName(channel).trim().split(' ').filter { it.isNotEmpty() && it !in byAlias }
        return " " + words.joinToString(" ") + " "
    }

    fun brandKey(brand: String): String = " " + TvMatch.norm(brand.replace("+", " plus ")).trim() + " "
}
