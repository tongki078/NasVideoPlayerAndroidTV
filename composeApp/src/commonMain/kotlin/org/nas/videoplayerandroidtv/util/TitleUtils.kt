package org.nas.videoplayerandroidtv.util

import org.nas.videoplayerandroidtv.toNfc

object TitleUtils {
    private val REGEX_EXT = Regex("""\.(?i)(?:mkv|mp4|avi|m4v|mov|wmv|asf|flv|webm|ts|tp|trp|m2ts|mts|mpg|mpeg|mpe|mpv|m2v|vob|ogv|divx|xvid)$""")
    private val REGEX_YEAR = Regex("""\((19|20)\d{2}\)|(?<!\d)(19|20)\d{2}(?!\d)""")
    private val REGEX_BRACKETS = Regex("""\[.*?(?:\]|$)|\(.*?(?:\)|$)|\{.*?(?:\)|$)|\【.*?(?:\】|$)|\『.*?(?:\』|$)|\「.*?(?:\」|$)|\（.*?(?:\）|$)""")
    private val REGEX_TMDB_HINT = Regex("""\{tmdb[\s-]*(\d+)\}""")
    private val REGEX_CH_PREFIX = Regex("""(?i)^\[(?:KBS|SBS|MBC|tvN|JTBC|OCN|Mnet|TV조선|채널A|MBN|ENA|KBS2|KBS1|CH\d+|TV|Netflix|Disney\+|AppleTV|NET|Wavve|Tving|Coupang)\]\s*""")
    
    // [개선] 불필요한 키워드 목록 대폭 보강 및 기술적 태그 통합
    private val REGEX_JUNK_KEYWORDS = Regex("""(?i)\s*(?:더빙|자막|극장판|BD|TV|Web|OAD|OVA|ONA|Full|무삭제|감독판|확장판|최종화|TV판|완결|속편|(?<=\s|^)[상하](?=\s|$)|\d+부|파트|LIMITED|RM4K|DC|THEATRICAL|EXTENDED|FINAL|REPACK|REMUX|10bit|BRRip|BDRip|HDRip|DVDRip|WEB-DL|WEBRip|Bluray|Blu-ray|h264|h265|x264|x265|hevc|avc|aac|dts|ac3|ddp|dd\+|TrueHD|Atmos|E-AC3|EAC3|Dual-Audio|Multi-Audio|Multi-Sub|xvid|divx|hallowed|Next|F1RST|CineWise|RAV|viki|DisneyPlus|DSNP|NF|Netflix|AMZN|Amazon|HULU|HBO|HMAX|ATVP|AppleTV|Wavve|Tving|Coupang|TVRip|HDTV|HD|NEXT|HDR10|HDR10Plus|Vision|Dolby|1080p|720p|480p|2160p|4K|FHD|QHD|UHD|1080i|720i|KOREAN|KOR|JAPANESE|JPN|CHINESE|CHN|ENGLISH|ENG|Mandarin|Cantonese|FanSub|2CH|5\.1CH|PROMO|RETAIL|B-Global|DV)\s*""")
    
    // [개선] 에피소드 마커 정규식 강화 (드래곤볼 다이아.e01.250421.1080p-KL 대응)
    // 날짜(\d{6})나 에피소드(E\d+)가 나오면 그 시점부터 끝까지 절단
    private val REGEX_EP_MARKER = Regex("""(?i)(?:[.\s_-]|[가-힣\u3040-\u30ff\u4e00-\u9fff])(?:第?\s*S(\d+)E(\d+)(?:[-~]E?\d+)?|第?\s*S(\d+)|第?\s*E(\d+)(?:[-~]\d+)?|\d+\s*(?:화|회|기|부|話)|Season\s*\d+|Part\s*\d+|pt\s*\d+|Episode\s*\d+|Disk\s*\d+|Disc\s*\d+|CD\s*\d+|시즌\s*\d+|[상하]부|최종화|\d{6}|\d{8}).*""")
    
    private val REGEX_LEADING_EP_MARKER = Regex("""(?i)^第?\s*S(\d+)E(\d+)(?:[-~]E?\d+)?|^第?\s*S(\d+)|^第?\s*E(\d+)|^Season\s*\d+|^Part\s*\d+|^Episode\s*\d+|^시즌\s*\d+|^\d+\s*(?:화|회|기|부|話)|^\d{6}|^\d{8}""")
    private val REGEX_LEADING_INDEX = Regex("""^(\d{1,5}\s+|(?:\d{1,5}\.(?!\d)\s*))""")
    private val REGEX_TRAILING_NUMBER = Regex("""\s+\d+$""")
    private val REGEX_SPECIAL_CHARS = Regex("""[\[\]()_\-\.!#@*※×,~:;【】『』「」"'（）]""")
    private val REGEX_SPACES = Regex("""\s+""")

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
        
        // 1. 기초 정제
        cleaned = REGEX_EXT.replace(cleaned, "")
        cleaned = REGEX_TMDB_HINT.replace(cleaned, "")
        cleaned = REGEX_CH_PREFIX.replace(cleaned, "")
        cleaned = cleaned.replace("×", "x").replace("✕", "x")
        
        // 2. 연도 정보 제거 (includeYear가 false일 때)
        if (!includeYear) {
            cleaned = REGEX_YEAR.replace(cleaned, " ")
        }
        
        // 3. 제목 앞에 오는 에피소드 마커 우선 제거
        cleaned = REGEX_LEADING_EP_MARKER.replace(cleaned, "").trim()
        
        // 4. 에피소드 마커 및 정크 키워드 제거 (이후 절단)
        // [중요] REGEX_EP_MARKER는 '드래곤볼 다이아.e01...'에서 '.e01'부터 끝까지 날려버림
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
        
        // 9. 제목 뒤의 숫자 제거 (단독 숫자만)
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
        Regex("""(?i)Episode\s*(\d+)""").find(this)?.let { return "${it.groupValues[1].toInt()}화" }
        return null
    }

    fun String.extractSeason(): Int {
        Regex("""(?i)[Ss](\d+)""").find(this)?.let { return it.groupValues[1].toInt() }
        Regex("""(?i)Season\s*(\d+)""").find(this)?.let { return it.groupValues[1].toInt() }
        return 1
    }

    fun String.prettyTitle(): String {
        val ep = this.extractEpisode()
        val cleaned = this.cleanTitle(keepAfterHyphen = false, includeYear = false) // 여기서 keepAfterHyphen을 false로 변경
        if (ep == null) return cleaned
        
        // 이미 제목에 화수 정보가 포함되어 있는지 확인
        if (cleaned.contains(ep) || cleaned.contains(ep.replace("화", "회"))) return cleaned

        return "$ep $cleaned".trim()
    }
}
