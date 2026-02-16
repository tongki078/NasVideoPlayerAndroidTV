package org.nas.videoplayerandroidtv.util

import org.nas.videoplayerandroidtv.toNfc

object TitleUtils {
    private val REGEX_EXT = Regex("""\.(?i)(?:mkv|mp4|avi|m4v|mov|wmv|asf|flv|webm|ts|tp|trp|m2ts|mts|mpg|mpeg|mpe|mpv|m2v|vob|ogv|divx|xvid)$""")
    private val REGEX_YEAR = Regex("""\((19|20)\d{2}\)|(?<!\d)(19|20)\d{2}(?!\d)""")
    private val REGEX_BRACKETS = Regex("""\[.*?(?:\]|$)|\(.*?(?:\)|$)|\{.*?(?:\)|$)|\【.*?(?:\】|$)|\『.*?(?:\』|$)|\「.*?(?:\」|$)|\（.*?(?:\）|$)""")
    private val REGEX_TMDB_HINT = Regex("""\{tmdb[\s-]*(\d+)\}""")
    private val REGEX_CH_PREFIX = Regex("""(?i)^\[(?:KBS|SBS|MBC|tvN|JTBC|OCN|Mnet|TV조선|채널A|MBN|ENA|KBS2|KBS1|CH\d+|TV|Netflix|Disney\+|AppleTV|NET|Wavve|Tving|Coupang)\]\s*""")
    private val REGEX_JUNK_KEYWORDS = Regex("""(?i)\s*(?:더빙|자막|극장판|BD|TV|Web|OAD|OVA|ONA|Full|무삭제|감독판|확장판|최종화|TV판|완결|속편|(?<=\s|^)[상하](?=\s|$)|\d+부|파트|LIMITED|RM4K|DC|THEATRICAL|EXTENDED|FINAL|REPACK|REMUX|10bit|BRRip|BDRip|HDRip|DVDRip|WEB-DL|WEBRip|Bluray|Blu-ray|h264|h265|x264|x265|hevc|avc|aac|dts|ac3|ddp|dd\+|TrueHD|Atmos|E-AC3|EAC3|Dual-Audio|Multi-Audio|Multi-Sub|xvid|divx|hallowed|Next|F1RST|CineWise|RAV|viki|DisneyPlus|DSNP|NF|Netflix|AMZN|Amazon|HULU|HBO|HMAX|ATVP|AppleTV|Wavve|Tving|Coupang|TVRip|HDTV|HDR10|HDR10Plus|Vision|Dolby|1080p|720p|480p|2160p|4K|FHD|UHD|QHD)\s*""")
    private val REGEX_TECHNICAL_TAGS = Regex("""(?i)[.\s_-](?!(?:\d+\b))(?:\d{3,4}p|2160p|FHD|QHD|UHD|4K|Bluray|Blu-ray|WEB-DL|WEBRip|HDRip|BDRip|DVDRip|H\.?26[45]|x26[45]|HEVC|AVC|AAC\d?|DTS-?H?D?|AC3|DDP\d?|DD\+\d?|Dual|Atmos|REPACK|10bit|REMUX|FLAC|xvid|DivX|MKV|MP4|AVI|HDR(?:10)?(?:\+)?|Vision|Dolby|NF|AMZN|HMAX|DSNP|AppleTV?|Disney|PCOK|playWEB|ATVP|HULU|HDTV|HD|NEXT|ST|SW|KL|YT|MVC|KN|FLUX|hallowed|PiRaTeS|Jadewind|Movie|pt\s*\d+|KOREAN|KOR|ITALIAN|JAPANESE|JPN|CHINESE|CHN|ENGLISH|ENG|USA|HK|TW|FRENCH|GERMAN|SPANISH|THAI|VIETNAMESE|WEB|DL|TVRip|HDR10Plus|IMAX|Unrated|REMASTERED|Criterion|NonDRM|BRRip|1080i|720i|국어|Mandarin|Cantonese|FanSub|VFQ|VF|2CH|5\.1CH|8m|2398|PROPER|PROMO|LIMITED|RM4K|DC|THEATRICAL|EXTENDED|FINAL|DUB|KORDUB|JAPDUB|ENGDUB|ARROW|EDITION|SPECIAL|COLLECTION|RETAIL|TVING|WAVVE|Coupang|CP|B-Global|TrueHD|E-AC3|EAC3|DV|Dual-Audio|Multi-Audio|Multi-Sub)(\b|$)""")
    private val REGEX_SPECIAL_CHARS = ("""[\[\]()_\-\.!#@*※×,~:;【】『』「」"'（）]""").toRegex()
    private val REGEX_SPACES = Regex("""\s+""")
    // 에피소드 마커: 중간에 나올 경우 그 이후를 절단하는 용도
    private val REGEX_EP_MARKER = Regex("""(?i)(?:[.\s_-]|[가-힣\u3040-\u30ff\u4e00-\u9fff])(?:第?\s*S(\d+)E(\d+)(?:[-~]E?\d+)?|第?\s*S(\d+)|第?\s*E(\d+)(?:[-~]\d+)?|\d+\s*(?:화|회|기|부|話)|Season\s*\d+|Part\s*\d+|pt\s*\d+|Episode\s*\d+|Disk\s*\d+|Disc\s*\d+|CD\s*\d+|시즌\s*\d+|[상하]부|최종화|\d{6}|\d{8}).*""")
    // 선행 에피소드 마커: 제목 앞에 올 경우 해당 부분만 제거하는 용도
    private val REGEX_LEADING_EP_MARKER = Regex("""(?i)^第?\s*S(\d+)E(\d+)(?:[-~]E?\d+)?|^第?\s*S(\d+)|^第?\s*E(\d+)|^Season\s*\d+|^Part\s*\d+|^Episode\s*\d+|^시즌\s*\d+|^\d+\s*(?:화|회|기|부|話)|^\d{6}|^\d{8}""")
    private val REGEX_LEADING_INDEX = Regex("""^(\d{1,5}\s+|(?:\d{1,5}\.(?!\d)\s*))""")
    private val REGEX_TRAILING_NUMBER = Regex("""\s+\d+$""")

    fun isGenericTitle(name: String?): Boolean {
        if (name.isNullOrBlank()) return true
        val n = name.trim().toNfc()
        val indexKeywords = Regex("""(?i)^\s*([0-9A-Z가-힣ㄱ-ㅎ]|0Z|0-Z|가-하|[0-9]-[0-9]|[A-Z]-[A-Z]|[가-힣]-[가-힣])\s*$""")
        if (indexKeywords.matches(n)) return true
        val yearKeywords = Regex("""(?i)^\s*(?:\(\d{4}\)|\d{4}|\d{4}\s*년)\s*$""")
        if (yearKeywords.matches(n)) return true
        val seasonKeywords = Regex("""(?i)Season\s*\d+|시즌\s*\d+|Part\s*\d+|파트\s*\d+|S\d+|\d+기|\d+화|\d+회""")
        val cleaned = seasonKeywords.replace(n, "").trim().replace(REGEX_SPECIAL_CHARS, "").trim()
        return cleaned.isEmpty() || (cleaned.length < 2 && !cleaned.any { it.isLetter() })
    }

    fun String.cleanTitle(keepAfterHyphen: Boolean = false, includeYear: Boolean = false): String {
        val normalized = this.toNfc()
        var cleaned = normalized
        
        // 1. 기초 정제: 확장자, TMDB 힌트, 채널 접두어 제거
        cleaned = REGEX_EXT.replace(cleaned, "")
        cleaned = REGEX_TMDB_HINT.replace(cleaned, "")
        cleaned = REGEX_CH_PREFIX.replace(cleaned, "")
        cleaned = cleaned.replace("×", "x").replace("✕", "x")
        
        // 2. 연도 정보 제거
        cleaned = REGEX_YEAR.replace(cleaned, " ")
        
        // 3. 제목 앞에 오는 에피소드 마커(S14E039 등) 우선 제거
        cleaned = REGEX_LEADING_EP_MARKER.replace(cleaned, "").trim()
        
        // 4. 기술적 태그 및 중간 에피소드 마커 제거 (이후 절단)
        cleaned = REGEX_TECHNICAL_TAGS.replace(cleaned, " ")
        cleaned = REGEX_EP_MARKER.replace(cleaned, " ") 
        cleaned = REGEX_JUNK_KEYWORDS.replace(cleaned, " ")
        
        // 5. 괄호 내용 제거
        cleaned = REGEX_BRACKETS.replace(cleaned, " ")
        cleaned = cleaned.replace("(자막)", "").replace("(더빙)", "").replace("[자막]", "").replace("[더빙]", "").replace("（자막）", "").replace("（더빙）", "")
        
        // 6. 하이픈 이후 처리
        if (!keepAfterHyphen && cleaned.contains("-")) {
            val parts = cleaned.split("-")
            val firstPart = parts[0].trim()
            val afterHyphen = parts.getOrNull(1)?.trim() ?: ""
            if (!(afterHyphen.length <= 3 && afterHyphen.all { it.isDigit() })) {
                cleaned = firstPart
            }
        }
        
        // 7. 선행 인덱스 제거 및 특수문자 제거
        cleaned = REGEX_LEADING_INDEX.replace(cleaned, "").trim()
        cleaned = REGEX_SPECIAL_CHARS.replace(cleaned, " ")
        
        // 8. 연속 공백 정리
        cleaned = REGEX_SPACES.replace(cleaned, " ").trim()
        
        // 9. 제목 뒤의 숫자 제거
        cleaned = REGEX_TRAILING_NUMBER.replace(cleaned, "").trim()
        
        // 너무 많이 지워진 경우 백업
        if (cleaned.length < 1 || (cleaned.length < 2 && !cleaned.any { it.isLetter() })) {
            val backup = normalized.replace(REGEX_TMDB_HINT, "").replace(REGEX_EXT, "").trim()
            return if (backup.length >= 2) backup else normalized
        }
        
        return cleaned
    }

    fun String.extractYear(): String? = REGEX_YEAR.find(this)?.value?.replace("(", "")?.replace(")", "")
    
    fun String.extractTmdbId(): Int? = REGEX_TMDB_HINT.find(this)?.groupValues?.get(1)?.toIntOrNull()

    fun String.extractEpisode(): String? {
        Regex("""(?i)[Ee](\d+)""").find(this)?.let { return "${it.groupValues[1].toInt()}화" }
        Regex("""(\d+)\s*(?:화|회|話)""").find(this)?.let { return "${it.groupValues[1].toInt()}화" }
        return null
    }

    fun String.extractSeason(): Int {
        Regex("""(?i)[Ss](\d+)""").find(this)?.let { return it.groupValues[1].toInt() }
        Regex("""(?i)Season\s*(\d+)""").find(this)?.let { return it.groupValues[1].toInt() }
        return 1
    }

    fun String.prettyTitle(): String {
        val ep = this.extractEpisode()
        val cleaned = this.cleanTitle(keepAfterHyphen = true, includeYear = false)
        if (ep == null) return cleaned
        if (cleaned.contains(" - ")) {
            val split = cleaned.split(" - ", limit = 2)
            val subTitle = split[1].trim()
            return "$ep $subTitle"
        }
        val epPattern = Regex("""\b${ep}\b""")
        if (epPattern.containsMatchIn(cleaned)) return cleaned

        return "$ep $cleaned".trim()
    }
}
