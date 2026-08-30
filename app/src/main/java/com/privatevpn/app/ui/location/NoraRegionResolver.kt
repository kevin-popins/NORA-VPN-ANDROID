package com.privatevpn.app.ui.location

import java.text.Normalizer
import java.util.Locale

internal data class NoraRegion(
    val isoCode: String,
    val labelRu: String,
    val flag: String,
    val backgroundNames: List<String> = emptyList()
)

private data class RegionDefinition(
    val region: NoraRegion,
    val isoCodes: Set<String>,
    val aliases: Set<String>,
    val tokenPrefixes: Set<String> = emptySet()
)

private data class NameToken(
    val normalized: String,
    val wasUppercase: Boolean
)

private val tokenPattern = Regex("[\\p{L}\\p{N}]+")
private val combiningMarks = Regex("\\p{M}+")

// These ISO codes are also common words. Lowercase forms only count when the
// name is short; uppercase forms such as "NORA AT 1" remain supported.
private val ambiguousWordIsoCodes = setOf("at", "be", "in", "it", "no")

private fun region(
    isoCode: String,
    labelRu: String,
    flag: String,
    isoCodes: Set<String>,
    aliases: Set<String>,
    tokenPrefixes: Set<String> = emptySet(),
    backgroundNames: List<String> = emptyList()
) = RegionDefinition(
    region = NoraRegion(isoCode, labelRu, flag, backgroundNames),
    isoCodes = isoCodes,
    aliases = aliases,
    tokenPrefixes = tokenPrefixes
)

private val regionDefinitions = listOf(
    region("NL", "Нидерланды", "🇳🇱", setOf("nl", "nld", "нл"), setOf("netherlands", "nederland", "dutch", "holland", "голландия", "amsterdam", "амстердам", "rotterdam", "роттердам"), setOf("нидерланд"), listOf("netherlands1", "netherlands2", "netherlands3")),
    region("DE", "Германия", "🇩🇪", setOf("de", "deu", "ger"), setOf("germany", "deutschland", "германия", "berlin", "берлин", "frankfurt", "франкфурт", "munich", "münchen", "мюнхен", "dusseldorf", "düsseldorf", "дюссельдорф"), setOf("герман"), listOf("germany1", "germany2", "germany3")),
    region("RU", "Россия", "🇷🇺", setOf("ru", "rus", "рф"), setOf("russia", "россия", "moscow", "москва", "spb", "питер", "петербург", "saint petersburg", "санкт петербург"), setOf("росси"), listOf("russia1", "russia2", "russia3")),
    region("FR", "Франция", "🇫🇷", setOf("fr", "fra"), setOf("france", "франция", "paris", "париж", "marseille", "марсель"), setOf("франц"), listOf("france1", "france2", "france3")),
    region("FI", "Финляндия", "🇫🇮", setOf("fi", "fin"), setOf("finland", "finnish", "suomi", "финляндия", "helsinki", "хельсинки"), setOf("финлянд"), listOf("finland1", "finland2", "finland3")),
    region("GB", "Великобритания", "🇬🇧", setOf("gb", "gbr", "uk"), setOf("united kingdom", "great britain", "england", "britain", "великобритания", "англия", "london", "лондон", "manchester", "манчестер"), setOf("британ"), listOf("uk1", "uk2", "uk3")),
    region("US", "США", "🇺🇸", setOf("us", "usa"), setOf("united states", "united states of america", "america", "american", "америка", "сша", "new york", "newyork", "ny", "нью йорк", "los angeles", "losangeles", "лос анджелес", "washington", "вашингтон"), backgroundNames = listOf("usa1", "usa2", "usa3")),
    region("IE", "Ирландия", "🇮🇪", setOf("ie", "irl"), setOf("ireland", "ирландия", "dublin", "дублин"), setOf("ирланд"), listOf("ireland", "ireland1", "ireland3")),
    region("IT", "Италия", "🇮🇹", setOf("it", "ita"), setOf("italy", "италия", "rome", "roma", "рим", "milan", "milano", "милан"), setOf("итал"), listOf("italy")),
    region("ES", "Испания", "🇪🇸", setOf("es", "esp"), setOf("spain", "españa", "испания", "madrid", "мадрид", "barcelona", "барселона"), setOf("испан"), listOf("spain")),
    region("EE", "Эстония", "🇪🇪", setOf("ee", "est"), setOf("estonia", "estonian", "eesti", "эстония", "tallinn", "таллин", "tartu", "тарту"), setOf("эстон"), listOf("estonia")),
    region("LT", "Литва", "🇱🇹", setOf("lt", "ltu"), setOf("lithuania", "lietuva", "литва", "vilnius", "вильнюс", "kaunas", "каунас"), setOf("литов"), listOf("lithuania1", "lithuania2", "lithuania3")),
    region("LV", "Латвия", "🇱🇻", setOf("lv", "lva"), setOf("latvia", "latvija", "латвия", "riga", "рига"), setOf("латви")),
    region("PL", "Польша", "🇵🇱", setOf("pl", "pol"), setOf("poland", "polska", "польша", "warsaw", "warszawa", "варшава", "krakow", "kraków", "краков"), setOf("польск"), listOf("poland1", "poland2", "poland3")),
    region("SE", "Швеция", "🇸🇪", setOf("se", "swe"), setOf("sweden", "sverige", "швеция", "stockholm", "стокгольм", "gothenburg", "göteborg", "гетеборг"), setOf("швед")),
    region("NO", "Норвегия", "🇳🇴", setOf("no", "nor"), setOf("norway", "norge", "норвегия", "oslo", "осло"), setOf("норвеж")),
    region("CH", "Швейцария", "🇨🇭", setOf("ch", "che"), setOf("switzerland", "schweiz", "швейцария", "zurich", "zürich", "цюрих", "geneva", "женева"), setOf("швейцар")),
    region("AT", "Австрия", "🇦🇹", setOf("at", "aut"), setOf("austria", "österreich", "австрия", "vienna", "wien", "вена"), setOf("австр")),
    region("TR", "Турция", "🇹🇷", setOf("tr", "tur"), setOf("turkey", "türkiye", "турция", "istanbul", "стамбул", "ankara", "анкара"), setOf("турец")),
    region("SG", "Сингапур", "🇸🇬", setOf("sg", "sgp"), setOf("singapore", "сингапур")),
    region("JP", "Япония", "🇯🇵", setOf("jp", "jpn"), setOf("japan", "япония", "tokyo", "токио", "osaka", "осака"), setOf("япон")),
    region("CA", "Канада", "🇨🇦", setOf("ca", "can"), setOf("canada", "канада", "toronto", "торонто", "montreal", "montréal", "монреаль", "vancouver", "ванкувер"), setOf("канад")),
    region("CZ", "Чехия", "🇨🇿", setOf("cz", "cze"), setOf("czechia", "czech republic", "czech", "чехия", "prague", "praha", "прага"), setOf("чеш")),
    region("UA", "Украина", "🇺🇦", setOf("ua", "ukr"), setOf("ukraine", "украина", "kyiv", "kiev", "киев", "київ", "odesa", "odessa", "одесса"), setOf("украин")),
    region("BY", "Беларусь", "🇧🇾", setOf("by", "blr"), setOf("belarus", "беларусь", "минск", "minsk"), setOf("белорус")),
    region("KZ", "Казахстан", "🇰🇿", setOf("kz", "kaz"), setOf("kazakhstan", "казахстан", "almaty", "алматы", "astana", "астана"), setOf("казах")),
    region("AE", "ОАЭ", "🇦🇪", setOf("ae", "are", "uae"), setOf("united arab emirates", "emirates", "эмираты", "оаэ", "dubai", "дубай", "abu dhabi", "абу даби")),
    region("IL", "Израиль", "🇮🇱", setOf("il", "isr"), setOf("israel", "израиль", "tel aviv", "telaviv", "тель авив", "jerusalem", "иерусалим"), setOf("израил")),
    region("PT", "Португалия", "🇵🇹", setOf("pt", "prt"), setOf("portugal", "португалия", "lisbon", "lisboa", "лиссабон", "porto", "порту"), setOf("португал"), listOf("portugal")),
    region("DK", "Дания", "🇩🇰", setOf("dk", "dnk"), setOf("denmark", "danmark", "дания", "copenhagen", "københavn", "копенгаген"), setOf("датск")),
    region("BE", "Бельгия", "🇧🇪", setOf("be", "bel"), setOf("belgium", "belgië", "бельгия", "brussels", "bruxelles", "брюссель", "antwerp", "антверпен"), setOf("бельг")),
    region("RO", "Румыния", "🇷🇴", setOf("ro", "rou"), setOf("romania", "românia", "румыния", "bucharest", "bucurești", "бухарест"), setOf("румын")),
    region("BG", "Болгария", "🇧🇬", setOf("bg", "bgr"), setOf("bulgaria", "bulgarian", "българия", "болгария", "sofia", "софия", "varna", "варна", "plovdiv", "пловдив", "burgas", "бургас", "nessebar", "nesebar", "несебр"), setOf("болгар", "българ"), listOf("bulgaria1", "bulgaria2", "bulgaria3")),
    region("RS", "Сербия", "🇷🇸", setOf("rs", "srb"), setOf("serbia", "srbija", "сербия", "belgrade", "beograd", "белград"), setOf("серб")),
    region("GR", "Греция", "🇬🇷", setOf("gr", "grc"), setOf("greece", "hellas", "греция", "athens", "афины", "thessaloniki", "салоники"), setOf("гречес")),
    region("HU", "Венгрия", "🇭🇺", setOf("hu", "hun"), setOf("hungary", "magyarország", "венгрия", "budapest", "будапешт"), setOf("венгер")),
    region("SK", "Словакия", "🇸🇰", setOf("sk", "svk"), setOf("slovakia", "slovensko", "словакия", "bratislava", "братислава"), setOf("словац")),
    region("SI", "Словения", "🇸🇮", setOf("si", "svn"), setOf("slovenia", "slovenija", "словения", "ljubljana", "любляна"), setOf("словен")),
    region("HR", "Хорватия", "🇭🇷", setOf("hr", "hrv"), setOf("croatia", "hrvatska", "хорватия", "zagreb", "загреб"), setOf("хорват")),
    region("MD", "Молдова", "🇲🇩", setOf("md", "mda"), setOf("moldova", "молдова", "chisinau", "chișinău", "кишинев", "кишинёв"), setOf("молдав")),
    region("IS", "Исландия", "🇮🇸", setOf("is", "isl"), setOf("iceland", "ísland", "исландия", "reykjavik", "reykjavík", "рейкьявик"), setOf("исланд")),
    region("LU", "Люксембург", "🇱🇺", setOf("lu", "lux"), setOf("luxembourg", "люксембург")),
    region("AL", "Албания", "🇦🇱", setOf("al", "alb"), setOf("albania", "shqipëria", "албания", "tirana", "тирана"), setOf("албан")),
    region("GE", "Грузия", "🇬🇪", setOf("ge", "geo"), setOf("georgia", "грузия", "tbilisi", "тбилиси", "batumi", "батуми"), setOf("грузин")),
    region("AM", "Армения", "🇦🇲", setOf("am", "arm"), setOf("armenia", "армения", "yerevan", "ереван"), setOf("армян")),
    region("AZ", "Азербайджан", "🇦🇿", setOf("az", "aze"), setOf("azerbaijan", "азербайджан", "baku", "баку"), setOf("азербайдж")),
    region("CY", "Кипр", "🇨🇾", setOf("cy", "cyp"), setOf("cyprus", "кипр", "nicosia", "никосия", "limassol", "лимасол"), setOf("кипрск")),
    region("CN", "Китай", "🇨🇳", setOf("cn", "chn"), setOf("china", "китай", "beijing", "пекин", "shanghai", "шанхай"), setOf("китайск"), listOf("china"))
)

internal fun resolveNoraRegion(profileName: String): NoraRegion? {
    val tokens = tokenize(profileName)
    if (tokens.isEmpty()) return null

    val normalizedTokenValues = tokens.map(NameToken::normalized)
    return regionDefinitions
        .filter { definition -> definition.matches(profileName, tokens, normalizedTokenValues) }
        .map(RegionDefinition::region)
        .distinctBy(NoraRegion::isoCode)
        .singleOrNull()
}

private fun RegionDefinition.matches(
    profileName: String,
    tokens: List<NameToken>,
    normalizedTokenValues: List<String>
): Boolean {
    if (profileName.contains(region.flag)) return true

    val isoMatch = isoCodes.any { code ->
        val normalizedCode = normalizeToken(code)
        tokens.any { token ->
            token.normalized == normalizedCode &&
                (normalizedCode !in ambiguousWordIsoCodes || token.wasUppercase || tokens.size <= 2)
        }
    }
    if (isoMatch) return true

    val aliasMatch = aliases.any { alias ->
        containsSequence(normalizedTokenValues, tokenize(alias).map(NameToken::normalized))
    }
    if (aliasMatch) return true

    return tokenPrefixes
        .map(::normalizeToken)
        .any { prefix -> normalizedTokenValues.any { token -> token.startsWith(prefix) } }
}

private fun tokenize(value: String): List<NameToken> = tokenPattern.findAll(value).map { match ->
    val original = match.value
    NameToken(
        normalized = normalizeToken(original),
        wasUppercase = original.any(Char::isLetter) && original.filter(Char::isLetter).all(Char::isUpperCase)
    )
}.filter { it.normalized.isNotEmpty() }.toList()

private fun normalizeToken(value: String): String = combiningMarks.replace(
    Normalizer.normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFD),
    ""
)

private fun containsSequence(tokens: List<String>, sequence: List<String>): Boolean {
    if (sequence.isEmpty() || sequence.size > tokens.size) return false
    return (0..tokens.size - sequence.size).any { start ->
        sequence.indices.all { offset -> tokens[start + offset] == sequence[offset] }
    }
}
