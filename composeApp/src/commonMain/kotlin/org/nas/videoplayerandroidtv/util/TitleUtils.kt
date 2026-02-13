package org.nas.videoplayerandroidtv.util

import org.nas.videoplayerandroidtv.toNfc

object TitleUtils {
    private val REGEX_EXT = Regex("""\.[a-zA-Z0-9]{2,4}$""")
    private val REGEX_YEAR = Regex("""\((19|20)\d{2}\)|(?<!\d)(19|20)\d{2}(?!\d)""")
    private val REGEX_BRACKETS = Regex("""\[.*?(?:\]|$)|\(.*?(?:\)|$)|\{.*?(?:\}|$)|\【.*?(?:\】|$)|\『.*?(?:\』|$)|\「.*?(?:\」|$)""")
    private val REGEX_TMDB_HINT = Regex("""\{tmdb[\s-]*(\d+)\}""")
    private val REGEX_JUNK_KEYWORDS = Regex("""(?i)\s*(?:더빙|자막|극장판|BD|TV|Web|OAD|OVA|ONA|Full|무삭제|감독판|확장판|최종화|TV판|완결|속편|(?<=\s|^)[상하](?=\s|$)|\d+부|파트)\s*""")
    
    // 기술적 태그 정규식 개선: 소수점(.) 뒤에 숫자가 바로 오는 경우(예: 2.5)는 제외하도록 Lookahead 사용
    private val REGEX_TECHNICAL_TAGS = Regex("""(?i)[.\s_](?!(?:\d+\b))(?:\d{3,4}p|WEB-DL|WEBRip|Bluray|HDRip|BDRip|DVDRip|H\.?26[45]|x26[45]|HEVC|AAC|DTS|AC3|DDP|Dual|Atmos|REPACK|10bit|REMUX|FLAC|xvid|DivX|MKV|MP4|AVI).*""")
    
    // 특수문자에서 '.'을 제외하여 '2.5' 같은 제목 보호
    private val REGEX_SPECIAL_CHARS = ("""[\[\]()_\-!?【】『』「」"'#@*※×]""").toRegex()
    private val REGEX_SPACES = Regex("""\s+""")
    private val REGEX_EP_MARKER = Regex("""(?i)(?:^|[.\s_]|(?<=[가-힣]))(?:S\d+E\d+|S\d+|E\d+|\d+\s*(?:화|회|기)|Season\s*\d+|Part\s*\d+).*""")

    val REGEX_INDEX_FOLDER = Regex("""(?i)^\s*([0-9A-Z가-힣ㄱ-ㅎ]|0Z|0-Z|가-하|[0-9]-[0-9]|[A-Z]-[A-Z]|[가-힣]-[가-힣])\s*$""")
    val REGEX_YEAR_FOLDER = Regex("""(?i)^\s*(?:\(\d{4}\)|\d{4}|\d{4}\s*년)\s*$""") 
    val REGEX_SEASON_FOLDER = Regex("""(?i)^\s*(?:Season\s*\d+|시즌\s*\d+(?:\s*년)?|Part\s*\d+|파트\s*\d+|S\d+|\d+기|\d+화|\d+회|특집|Special|Extras|Bonus|미분류|기타|새\s*폴더)\s*$""")
    val REGEX_GENERIC_MANAGEMENT_FOLDER = Regex("""(?i)^\s*(?:특집|Special|Extras|Bonus|미분류|기타|새\s*폴더)\s*$""")

    val REGEX_GROUP_BY_SERIES = Regex(
        """(?i)[\s._-]*(?:s\d{1,2}e\d{1,3} |season\s*\d{1,2} |s\d{1,2} |ep\d{1,3}|e\d{1,3}|\d{1,3}화|\d{1,3}회|\d+기|part\s*\d|극장판|완결|special|extras|ova|720p|1080p|2160p|4k|h264|h265|x264|x265|bluray|web-dl|aac|mp4|mkv|avi|\([^)]*\)|\[[^\]]*\]).*|(?:\s+\d+\s*$)""", 
        RegexOption.IGNORE_CASE
    )

    fun isGenericTitle(name: String?): Boolean {
        if (name.isNullOrBlank()) return true
        val n = name.trim().toNfc()
        if (REGEX_INDEX_FOLDER.matches(n) || REGEX_YEAR_FOLDER.matches(n) || REGEX_SEASON_FOLDER.matches(n) || REGEX_GENERIC_MANAGEMENT_FOLDER.matches(n)) return true
        val seasonKeywords = Regex("""(?i)Season\s*\d+|시즌\s*\d+|Part\s*\d+|파트\s*\d+|S\d+|\d+기|\d+화|\d+회""")
        val cleaned = seasonKeywords.replace(n, "").trim().replace(REGEX_SPECIAL_CHARS, "").trim()
        return cleaned.isEmpty() || (cleaned.length < 2 && !cleaned.any { it.isLetter() })
    }

    fun String.cleanTitle(keepAfterHyphen: Boolean = false, includeYear: Boolean = true): String {
        val normalized = this.toNfc()
        var cleaned = normalized
        cleaned = cleaned.replace("×", "x").replace("✕", "x")
        cleaned = REGEX_TMDB_HINT.replace(cleaned, "")
        cleaned = REGEX_EXT.replace(cleaned, "")
        cleaned = REGEX_EP_MARKER.replace(cleaned, "") 
        val yearMatch = REGEX_YEAR.find(cleaned)
        val yearStr = yearMatch?.value?.replace("(", "")?.replace(")", "")
        cleaned = REGEX_YEAR.replace(cleaned, " ")
        cleaned = REGEX_BRACKETS.replace(cleaned, " ")
        cleaned = cleaned.replace("(자막)", "").replace("(더빙)", "").replace("[자막]", "").replace("[더빙]", "")
        cleaned = REGEX_JUNK_KEYWORDS.replace(cleaned, " ")
        cleaned = REGEX_TECHNICAL_TAGS.replace(cleaned, "")
        
        if (!keepAfterHyphen && cleaned.contains("-")) {
            val parts = cleaned.split("-")
            val firstPart = parts[0].trim()
            val afterHyphen = parts.getOrNull(1)?.trim() ?: ""
            if (!(afterHyphen.length <= 3 && afterHyphen.all { it.isDigit() })) {
                cleaned = firstPart
            }
        }
        
        cleaned = cleaned.replace(":", " ")
        cleaned = REGEX_SPECIAL_CHARS.replace(cleaned, " ")
        cleaned = REGEX_SPACES.replace(cleaned, " ").trim()

        if (cleaned.length < 2) {
            val backup = normalized.replace(REGEX_TMDB_HINT, "").replace(REGEX_EXT, "").trim()
            return if (backup.length >= 2) backup else normalized
        }
        return if (includeYear && yearStr != null) "$cleaned ($yearStr)" else cleaned
    }

    fun String.extractYear(): String? = REGEX_YEAR.find(this)?.value?.replace("(", "")?.replace(")", "")
    fun String.extractTmdbId(): Int? = REGEX_TMDB_HINT.find(this)?.groupValues?.get(1)?.toIntOrNull()

    fun String.extractEpisode(): String? {
        Regex("""(?i)[Ee](\d+)""").find(this)?.let { return "${it.groupValues[1].toInt()}화" }
        Regex("""(\d+)\s*(?:화|회|화|회)""").find(this)?.let { return "${it.groupValues[1].toInt()}화" }
        return null
    }

    fun String.extractSeason(): Int {
        Regex("""(?i)[Ss](\d+)""").find(this)?.let { return it.groupValues[1].toInt() }
        Regex("""(?i)Season\s*(\d+)""").find(this)?.let { return it.groupValues[1].toInt() }
        Regex("""(?i)Part\s*(\d+)""").find(this)?.let { return it.groupValues[1].toInt() }
        Regex("""(\d+)\s*(?:기|기)""").find(this)?.let { return it.groupValues[1].toInt() }
        return 1
    }

    fun String.prettyTitle(): String {
        val ep = this.extractEpisode()
        val base = this.cleanTitle(keepAfterHyphen = true, includeYear = false)
        if (ep == null) return base
        return if (base.contains(" - ")) { 
            val split = base.split(" - ", limit = 2)
            "${split[0]} $ep - ${split[1]}" 
        } else "$base $ep"
    }
}
