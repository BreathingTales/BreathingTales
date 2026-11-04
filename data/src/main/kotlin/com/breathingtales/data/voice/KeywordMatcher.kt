package com.breathingtales.data.voice

/**
 * 외치기 키워드 판정 (08). 아이 발음과 STT 오인식을 감안해 느슨하게 본다.
 * - 띄어쓰기·문장부호는 무시한다 ("바람아 불어라!" = "바람아불어라")
 * - 들린 문장 어디에든 키워드가 들어 있으면 성공 ("음 바람아" → "바람아")
 * - 한글을 자모로 풀어 비교하고, 키워드가 길면 자모 몇 개 틀림까지 성공한다.
 *   받침이 넘어가는 발음("바람아" → "바라마")은 글자로는 두 글자가 다르지만 자모로는 하나만 다르다.
 */
class KeywordMatcher(keywords: List<String>) {

    private class Target(val keyword: String, val jamo: String) {
        /**
         * 자모 5개(약 두 글자) 이상이면 1개, 10개(약 네 글자) 이상이면 2개까지 틀려도 된다.
         * 에뮬레이터 STT가 "해님 반짝"을 "해님 단짠", "해님 반찬"으로 듣는 경우(자모 2개 차이)를 받으려는 것
         */
        val allowedEdits = when {
            jamo.length >= 10 -> 2
            jamo.length >= 5 -> 1
            else -> 0
        }
    }

    private val targets = keywords.map(::normalize).filter { it.isNotEmpty() }.distinct()
        .map { Target(it, toJamo(it)) }

    /** 맞은 키워드(띄어쓰기를 뺀 형태). 없으면 null */
    fun match(heard: String): String? {
        val text = normalize(heard)
        if (text.isEmpty()) return null
        targets.firstOrNull { text.contains(it.keyword) }?.let { return it.keyword }
        val jamo = toJamo(text)
        return targets.firstOrNull { it.allowedEdits > 0 && containsApproximately(jamo, it) }?.keyword
    }

    /** [text] 안에 [target]과 편집 거리가 허용치 이하인 구간이 있는지 */
    private fun containsApproximately(text: String, target: Target): Boolean {
        val n = target.jamo.length
        val k = target.allowedEdits
        for (length in (n - k).coerceAtLeast(1)..n + k) {
            if (length > text.length) continue
            for (start in 0..text.length - length) {
                if (editDistance(text.substring(start, start + length), target.jamo) <= k) return true
            }
        }
        return false
    }

    private fun editDistance(a: String, b: String): Int {
        var previous = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val current = IntArray(b.length + 1)
            current[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + cost)
            }
            previous = current
        }
        return previous[b.length]
    }

    private companion object {
        fun normalize(text: String): String = text.filter { it.isLetterOrDigit() }.lowercase()

        const val INITIALS = "ㄱㄲㄴㄷㄸㄹㅁㅂㅃㅅㅆㅇㅈㅉㅊㅋㅌㅍㅎ"
        const val VOWELS = "ㅏㅐㅑㅒㅓㅔㅕㅖㅗㅘㅙㅚㅛㅜㅝㅞㅟㅠㅡㅢㅣ"
        val FINALS = listOf("", "ㄱ", "ㄲ", "ㄱㅅ", "ㄴ", "ㄴㅈ", "ㄴㅎ", "ㄷ", "ㄹ", "ㄹㄱ", "ㄹㅁ", "ㄹㅂ", "ㄹㅅ", "ㄹㅌ",
            "ㄹㅍ", "ㄹㅎ", "ㅁ", "ㅂ", "ㅂㅅ", "ㅅ", "ㅆ", "ㅇ", "ㅈ", "ㅊ", "ㅋ", "ㅌ", "ㅍ", "ㅎ")

        /**
         * 완성형 한글을 자모로 푼다. 받침과 초성을 같은 글자로 써서 받침이 넘어가는 발음을 맞추고,
         * 소리 없는 초성 ㅇ은 뺀다 ("바람아" = "바라마" = ㅂㅏㄹㅏㅁㅏ). 한글이 아니면 그대로
         */
        fun toJamo(text: String): String = buildString {
            for (c in text) {
                if (c in '가'..'힣') {
                    val index = c - '가'
                    val initial = INITIALS[index / 588]
                    if (initial != 'ㅇ') append(initial)
                    append(VOWELS[index % 588 / 28])
                    append(FINALS[index % 28])
                } else {
                    append(c)
                }
            }
        }
    }
}
