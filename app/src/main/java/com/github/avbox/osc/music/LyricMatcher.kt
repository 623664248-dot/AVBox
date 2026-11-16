package com.github.avbox.osc.music

import com.github.avbox.osc.util.AppLog

/**
* Lyric matching for py spider tracks (v1.13.3). Spiders provide no
* lyrics, so after a spider song starts playing the player asks this
* matcher to find the same song on a platform (NetEase first, then QQ)
* and reuse the platform's existing lyric endpoint. Playback never waits
* for this: the caller runs it in parallel and fills the lyric state
* when (and if) a match lands; any failure simply keeps "no lyrics".
*
* Matching is deliberately strict about the title — a wrong song's
* lyric is worse than none — and lenient about the singer, which spider
* remarks often mangle ("周深 周深歌曲").
*/
object LyricMatcher {

/** Platforms tried in order, through [MusicApi]'s existing search/lyric. */
private val PLATFORMS = listOf("wy", "tx")

/**
* Raw LRC text for [track], or null when nothing matched. Never
* throws: every step is best effort.
*/
suspend fun matchLyric(track: MusicTrack): String? {
return try {
val singers = cleanSingers(track.singer)
val titles = titleCandidates(track.name, singers)
for (title in titles) {
val keyword = if (singers.isEmpty()) {
title
} else {
title + " " + singers.first()
}
for (platform in PLATFORMS) {
val lyric = tryPlatform(platform, keyword, title, singers)
if (!lyric.isNullOrBlank()) return lyric
}
}
null
} catch (e: Exception) {
AppLog.e("LyricMatcher", e)
null
}
}

private suspend fun tryPlatform(
platform: String,
keyword: String,
title: String,
singers: List<String>,
): String? {
val candidates = MusicApi.search(platform, keyword, 1)
val match = pickMatch(candidates, title, singers)?: return null
return MusicApi.lyric(match)
}

/**
* Picks the candidate whose name matches [title] exactly (after
* normalization and stripping a trailing bracketed suffix such as
* "（Live）"). Among exact-title candidates, one whose singer
* overlaps the spider's singer wins; with no singer information a
* bare title match is accepted.
*/
private fun pickMatch(
candidates: List<MusicTrack>,
title: String,
singers: List<String>,
): MusicTrack? {
val titleMatches = candidates.filter { nameMatches(it.name, title)}
if (titleMatches.isEmpty()) return null
if (singers.isEmpty()) return titleMatches.first()
val singerHit = titleMatches.firstOrNull { candidate ->
val candidateSinger = normalize(candidate.singer)
candidateSinger.isNotEmpty() && singers.any { token ->
val normalizedToken = normalize(token)
normalizedToken.isNotEmpty() &&
(candidateSinger.contains(normalizedToken) ||
normalizedToken.contains(candidateSinger))
}
}
return singerHit?: titleMatches.first()
}

// ------------------------------------------------------------ cleaning

/**
* Ordered title candidates for a spider song name, best first:
* the 《》-bracketed title when present ("周深《小美满》" → "小美满"),
* then the non-singer side of a " - " split, then the other side,
* then the whole name with brackets removed.
*/
private fun titleCandidates(rawName: String, singers: List<String>): List<String> {
val name = rawName.trim()
if (name.isEmpty()) return emptyList()
val candidates = ArrayList<String>()
val bracketed = Regex("《([^》]+)》").find(name)?.groupValues?.get(1)?.trim()
if (!bracketed.isNullOrEmpty()) candidates.add(bracketed)
val unbracketed = name
.replace("《", "")
.replace("》", "")
.replace("", "")
.trim()
val parts = unbracketed.split(" - ").map { it.trim()}.filter { it.isNotEmpty()}
if (parts.size == 2) {
val firstIsSinger = isSingerLike(parts[0], singers)
val secondIsSinger = isSingerLike(parts[1], singers)
when {
secondIsSinger &&!firstIsSinger -> {
candidates.add(parts[0])
candidates.add(parts[1])
}

firstIsSinger &&!secondIsSinger -> {
candidates.add(parts[1])
candidates.add(parts[0])
}

else -> {
candidates.add(parts[0])
candidates.add(parts[1])
}
}
}
if (unbracketed.isNotEmpty()) candidates.add(unbracketed)
return candidates.distinct().take(3)
}

/** A " - " side looks like the singer when it lists several names or matches the singer field. */
private fun isSingerLike(part: String, singers: List<String>): Boolean {
if (part.contains("/") || part.contains("、")) return true
val normalizedPart = normalize(part)
if (normalizedPart.isEmpty()) return false
return singers.any { token ->
val normalizedToken = normalize(token)
normalizedToken.isNotEmpty() &&
(normalizedPart == normalizedToken || normalizedPart.contains(normalizedToken))
}
}

/**
* Cleans a spider singer/remarks string into singer tokens: split on
* the usual separators, strip junk suffixes spiders append
* ("周深歌曲" → "周深"), drop empties and duplicates.
*/
private fun cleanSingers(rawSinger: String): List<String> {
val tokens = ArrayList<String>()
for (raw in rawSinger.split(Regex("[/、,，&;；\\s]+"))) {
var token = raw.trim()
var stripped = true
while (stripped) {
stripped = false
for (suffix in JUNK_SUFFIXES) {
if (token.length > suffix.length && token.endsWith(suffix)) {
token = token.dropLast(suffix.length).trim()
stripped = true
}
}
}
if (token.isNotEmpty() && token.length <= 20 && token!in tokens) {
tokens.add(token)
}
}
return tokens
}

private val JUNK_SUFFIXES = listOf("歌曲", "音乐", "合集", "专辑", "精选")

// ------------------------------------------------------------ comparing

private fun nameMatches(candidateName: String, title: String): Boolean {
val candidate = normalize(candidateName)
val wanted = normalize(title)
if (candidate.isEmpty() || wanted.isEmpty()) return false
if (candidate == wanted) return true
// One side may carry a trailing bracketed tag the other lacks
// ("发如雪 (Live)" vs "发如雪").
return normalize(stripBracketSuffix(candidateName)) == wanted ||
candidate == normalize(stripBracketSuffix(title))
}

private fun stripBracketSuffix(name: String): String {
return name.replace(Regex("[（(][^（）()]*[)）]\\s*$"), "").trim()
}

/** Lowercases and removes all whitespace and punctuation for comparison. */
private fun normalize(text: String): String {
return text.lowercase().replace(
Regex("[\\s\\p{Punct}《》「」『』〈〉·・…—–~！？：；，。'\"“”‘’]+"),
"",
)
}
}
