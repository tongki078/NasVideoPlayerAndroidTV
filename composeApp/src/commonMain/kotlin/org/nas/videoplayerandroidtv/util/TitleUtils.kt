package org.nas.videoplayerandroidtv.util

import org.nas.videoplayerandroidtv.toNfc

object TitleUtils {
    private val REGEX_EXT = Regex("""\.[a-zA-Z0-9]{2,4}$""")
    private val REGEX_YEAR = Regex("""\((19|20)\d{2}\)|(?<!\d)(19|20)\d{2}(?!\d)""")
    private val REGEX_BRACKETS = Regex("""\[.*?(?:\]|$)|\(.*?(?:\)|$)|\{.*?(?:\}|$)|\【.*?(?:\】|$)|\『.*?(?:\』|$)|\「.*?(?:\」|$)|\（.*?(?:\）|$)""")
    private val REGEX_TMDB_HINT = Regex("""\{tmdb[\s-]*(\d+)\}""")
    
    // 쓰레기 키워드 대폭 확장
    private val REGEX_JUNK_KEYWORDS = Regex("""(?i)\s*(?:더빙|자막|극장판|BD|TV|Web|OAD|OVA|ONA|Full|무삭제|감독판|확장판|최종화|TV판|완결|속편|(?<=\s|^)[상하](?=\s|$)|\d+부|파트|LIMITED|RM4K|DC|THEATRICAL|EXTENDED|FINAL|REPACK|REMUX|10bit|BRRip|BDRip|HDRip|DVDRip|WEB-DL|WEBRip|Bluray|Blu-ray|h264|h265|x264|x265|hevc|avc|aac|dts|ac3|ddp|dd\+)\s*""")
    
    // 기술적 태그 정규식 개선 (server.py 수준으로 강화)
    private val REGEX_TECHNICAL_TAGS = Regex("""(?i)[.\s_-](?!(?:\d+\b))(?:\d{3,4}p|FHD|QHD|UHD|4K|Bluray|Blu-ray|WEB-DL|WEBRip|HDRip|BDRip|DVDRip|H\.?26[45]|x26[45]|HEVC|AVC|AAC\d?|DTS-?H?D?|AC3|DDP\d?|DD\+\d?|Dual|Atmos|REPACK|10bit|REMUX|FLAC|xvid|DivX|MKV|MP4|AVI|HDR(?:10)?(?:\+)?|Vision|Dolby|NF|AMZN|HMAX|DSNP|AppleTV?|Disney|PCOK|playWEB|ATVP|HULU|HDTV|HD|KBS|SBS|MBC|TVN|JTBC|NEXT|ST|SW|KL|YT|MVC|KN|FLUX|hallowed|PiRaTeS|Jadewind|Movie|pt\s*\d+|KOREAN|KOR|ITALIAN|JAPANESE|JPN|CHINESE|CHN|ENGLISH|ENG|USA|HK|TW|FRENCH|GERMAN|SPANISH|THAI|VIETNAMESE|WEB|DL|TVRip|HDR10Plus|IMAX|Unrated|REMASTERED|Criterion|NonDRM|BRRip|1080i|720i|국어|Mandarin|Cantonese|FanSub|VFQ|VF|2CH|5\.1CH|8m|2398|PROPER|PROMO|LIMITED|RM4K|DC|THEATRICAL|EXTENDED|FINAL|DUB|KORDUB|JAPDUB|ENGDUB|ARROW|EDITION|SPECIAL|COLLECTION|RETAIL)(\b|$)""")
    
    private val REGEX_SPECIAL_CHARS = ("""[\[\]()_\-!?【】『』「」"'#@*※×,~:;（）]""").toRegex()
    private val REGEX_SPACES = Regex("""\s+""")
    
    // 에피소드 마커 정규식 강화
    private val REGEX_EP_MARKER = Regex("""(?i)(?:^|[.\s_-]|[가-힣\u3040-\u30ff\u4e00-\u9fff])(?:第?\s*S(\d+)E(\d+)(?:[-~]E?\d+)?|第?\s*S(\d+)|第?\s*E(\d+)(?:[-~]\d+)?|\d+\s*(?:화|회|기|부|話)|Season\s*\d+|Part\s*\d+|pt\s*\d+|Episode\s*\d+|Disk\s*\d+|Disc\s*\d+|CD\s*\d+|시즌\s*\d+|[상하]부|최종화|\d{6}|\d{8}).*""")

    private val REGEX_LEADING_INDEX = Regex("""^(\d{1,5}\s+|(?:\d{1,5}\.(?!\d)\s*))""")

    val REGEX_INDEX_FOLDER = Regex("""(?i)^\s*([0-9A-Z가-힣ㄱ-ㅎ]|0Z|0-Z|가-하|[0-9]-[0-9]|[A-Z]-[A-Z]|[가-힣]-[가-힣])\s*$""")
    val REGEX_YEAR_FOLDER = Regex("""(?i)^\s*(?:\(\d{4}\)|\d{4}|\d{4}\s*년)\s*$""") 
    val REGEX_SEASON_FOLDER = Regex("""(?i)^\s*(?:Season\s*\d+|시즌\s*\d+(?:\s*년)?|Part\s*\d+|파트\s*\d+|S\d+|\d+기|\d+화|\d+회|특집|Special|Extras|Bonus|미분류|기타|새\s*폴더)\s*$""")
    val REGEX_GENERIC_MANAGEMENT_FOLDER = Regex("""(?i)^\s*(?:특집|Special|Extras|Bonus|미분류|기타|새\s*폴더)\s*$""")

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
        
        cleaned = REGEX_LEADING_INDEX.replace(cleaned, "").trim()
        cleaned = cleaned.replace("×", "x").replace("✕", "x")
        cleaned = REGEX_TMDB_HINT.replace(cleaned, "")
        cleaned = REGEX_EXT.replace(cleaned, "")
        
        // 기술적 태그 먼저 제거
        cleaned = REGEX_TECHNICAL_TAGS.replace(cleaned, "")
        
        // 에피소드 마커 제거 (에피소드 번호 포함 뒷부분 통째로 날림)
        cleaned = REGEX_EP_MARKER.replace(cleaned, "") 
        
        val yearMatch = REGEX_YEAR.find(cleaned)
        val yearStr = yearMatch?.value?.replace("(", "")?.replace(")", "")
        cleaned = REGEX_YEAR.replace(cleaned, " ")
        cleaned = REGEX_BRACKETS.replace(cleaned, " ")
        cleaned = cleaned.replace("(자막)", "").replace("(더빙)", "").replace("[자막]", "").replace("[더빙]", "").replace("（자막）", "").replace("（더빙）", "")
        cleaned = REGEX_JUNK_KEYWORDS.replace(cleaned, " ")
        
        if (!keepAfterHyphen && cleaned.contains("-")) {
            val parts = cleaned.split("-")
            val firstPart = parts[0].trim()
            val afterHyphen = parts.getOrNull(1)?.trim() ?: ""
            // 하이픈 뒤가 짧은 숫자면 제목의 일부일 수 있음 (에피소드 번호 등)
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
        Regex("""(\d+)\s*(?:화|회|화|회|話)""").find(this)?.let { return "${it.groupValues[1].toInt()}화" }
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
        // 에피소드 리스트에서 보여줄 때는 시리즈 제목을 최대한 억제하고 에피소드 정보 위주로 표시
        val cleaned = this.cleanTitle(keepAfterHyphen = true, includeYear = false)
        
        if (ep == null) return cleaned
        
        // 만약 제목이 "포켓몬스터 1화 - 피카츄" 형태라면 "1화 피카츄" 정도로만 보여주는게 깔끔함
        if (cleaned.contains(" - ")) {
            val split = cleaned.split(" - ", limit = 2)
            val subTitle = split[1].trim()
            return "$ep $subTitle"
        }
        
        return "$ep $cleaned".trim()
    }
}
