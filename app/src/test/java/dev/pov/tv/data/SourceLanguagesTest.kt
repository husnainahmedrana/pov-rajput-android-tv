package dev.pov.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceLanguagesTest {
    private fun source(title: String, height: Int = 1080, detail: String = "") = SourceItem(title, "fixture", detail = detail, qualityHeight = height)

    @Test fun `a long Torrentio flag line is the original audio and then subtitles`() {
        // Torrentio, Resident Evil (2026): a PSA encode lists 27 flags; only English is audio.
        val psa = SourceLanguages.detect("Resident.Evil.2026.2160p.HDR10Plus.DV.WEBRip.6CH.x265.HEVC-PSA\n👤 220 💾 2.19 GB ⚙️ EXT\n" +
            "🇬🇧 / 🇯🇵 / 🇷🇺 / 🇮🇹 / 🇵🇹 / 🇪🇸 / 🇰🇷 / 🇨🇳 / 🇫🇷 / 🇳🇱 / 🇵🇱 / 🇨🇿 / 🇭🇺 / 🇧🇬 / 🇷🇸   / 🇭🇷 / 🇺🇦")
        assertEquals(setOf("en"), psa.audio)
        assertTrue("es" in psa.subtitles)
        // A short list is real dubs.
        assertEquals(setOf("en", "es"), SourceLanguages.detect("Resident Evil (2026) [Bluray 1080p][Esp]\n👤 412 💾 7.01 GB ⚙️ Wolfmax4k\n🇬🇧 / 🇪🇸").audio)
        // AIOStreams / Comet: the same long list on an unlabelled 🌎 line.
        val aio = SourceLanguages.detect("Resident.Evil.2026.2160p.WEBRip-PSA\n🌎 🇬🇧 / 🇯🇵 / 🇷🇺 / 🇮🇹 / 🇪🇸 / 🇫🇷")
        assertEquals(setOf("en"), aio.audio)
        assertTrue("es" in aio.subtitles)
        // Meteor labels both lines, so a long 🌐 line stays audio.
        val meteor = SourceLanguages.detect("📄 Movie.2160p.MULTi.mkv\n🌐 🇬🇧 🇪🇸 🇵🇹 🇫🇷 🇮🇹\n💬 🇬🇧 🇪🇸")
        assertEquals(setOf("en", "es", "pt", "fr", "it"), meteor.audio)
        // StremThru: 🎙️ audio with its own 💬 subtitles line, or one long 🌐 line when subtitles are unknown.
        val labelled = SourceLanguages.detect("💿 WEBRip 🎞️ HEVC\n📦 2.19 GB 👤 220\n🎙️ 🇬🇧 🇯🇵 🇷🇺 🇮🇹 🇪🇸\n💬 🇫🇷 🇪🇸")
        assertEquals(setOf("en", "ja", "ru", "it", "es"), labelled.audio)
        assertEquals(setOf("fr", "es"), labelled.subtitles)
        val unlabelled = SourceLanguages.detect("💿 WEBRip 🎞️ HEVC\n📦 2.19 GB 👤 220\n🌐 🇬🇧 🇯🇵 🇷🇺 🇮🇹 🇪🇸\n⚙️ PSA 🔗 EXT")
        assertEquals(setOf("en"), unlabelled.audio)
        val spanish = SourceLanguageOrder("es", "es")
        val items = listOf(source("Resident.Evil.2026.2160p.HDR10Plus.DV.WEBRip.6CH.x265.HEVC-PSA", 2160,
            "🇬🇧 / 🇯🇵 / 🇷🇺 / 🇮🇹 / 🇵🇹 / 🇪🇸 / 🇰🇷 / 🇨🇳 / 🇫🇷"), source("Resident Evil (2026) [4K 2160P][Esp]", 2160, "🇪🇸"))
        assertEquals("Resident Evil (2026) [4K 2160P][Esp]", spanish.sorted(items, SourceSortOrder()).first().title)
    }

    @Test fun `subtitle lists in Torrentio and Comet release names are subtitles, not audio`() {
        // Torrentio (Rutracker): every language after "Sub" is a subtitle, including after an unknown code (Ukr).
        val rutracker = SourceLanguages.detect("Dune: Part Two [2024 UHD BDRemux 2160p] 4x Dub + Sub Rus Ukr Eng Multi 4 + Original Eng\n👤 395 💾 69 GB ⚙️ Rutracker\nMulti Audio / 🇬🇧 / 🇷🇺 / 🇺🇦")
        assertEquals(setOf("ru", "en"), rutracker.subtitles)
        assertTrue("Original Eng and the flag line keep English audio", "en" in rutracker.audio)
        val bracketed = SourceLanguages.detect("Dune: Part Two [2024 WEB-DL 2160p] Dub + (Ukr) + Original (Eng) + Sub (Rus Eng Ukr)")
        assertEquals(setOf("ru", "en"), bracketed.subtitles)
        assertEquals(setOf("en"), bracketed.audio)
        // Comet: "Sub Ita Eng" and "sub ita.eng" after the audio languages.
        val comet = SourceLanguages.detect("📄 Dune - Parte Due (2024) 2160p H265 DV ITA TrueHD DA 7.1 ENG ITA AC3 5.1 Sub Ita Eng NUIta NUEng - Raffyone.mkv")
        assertEquals(setOf("it", "en"), comet.audio)
        assertEquals(setOf("it", "en"), comet.subtitles)
        assertEquals(setOf("it", "en"), SourceLanguages.detect("Dune - Part Two (2024) AC3 5.1 ITA.ENG 2160p H265 HDR10 Dolby Vision sub ita.eng Sp33dy94-MIRCrew.mkv").subtitles)
        // "ESub": English subtitles.
        val esub = SourceLanguages.detect("📄 Dune- Part Two (2024) 2160p 4K UHD Blu-ray REMUX Dual Audio [Hindi + English] ESub ~ RemuxDoc.mkv")
        assertEquals(setOf("hi", "en"), esub.audio)
        assertEquals(setOf("en"), esub.subtitles)
        // Meteor's 🌐 / 💬 lines keep working.
        val meteor = SourceLanguages.detect("📄 Dune.Part.Two.2024.UHD.BluRay.2160p.REMUX-FraMeSToR.mkv\n🌐 🇬🇧\n💬 🇬🇧 🇫🇷 🇩🇪 🇮🇹")
        assertEquals(setOf("en"), meteor.audio)
        assertEquals(setOf("en", "fr", "de", "it"), meteor.subtitles)
    }

    @Test fun `release names, native names and flags are read as audio languages`() {
        assertEquals(setOf("pt"), SourceLanguages.detect("Movie.2024.1080p.WEB-DL.DUAL.PT-BR.mkv").audio)
        assertEquals(setOf("pt", "en"), SourceLanguages.detect("Movie 2024 🇧🇷 / 🇬🇧").audio.toSet())
        assertEquals(setOf("es"), SourceLanguages.detect("Movie.2024.LATINO.1080p").audio)
        assertEquals(setOf("pt"), SourceLanguages.detect("Filme Português 1080p").audio)
        assertTrue(SourceLanguages.detect("Movie.2024.MULTi.1080p").multiAudio)
        assertFalse(SourceLanguages.detect("Movie.2024.Multi-Subs.1080p").multiAudio)
        // Two-letter words never count on their own.
        assertTrue(SourceLanguages.detect("It Ends With Us 2024 1080p").audio.isEmpty())
    }

    @Test fun `subtitle mentions are kept apart from audio`() {
        val found = SourceLanguages.detect("Movie.2024.1080p.Legendado.mkv · Subs: Portuguese")
        assertTrue("pt" in found.subtitles); assertFalse("pt" in found.audio)
        val french = SourceLanguages.detect("Film.2024.VOSTFR.1080p")
        assertTrue("fr" in french.subtitles); assertFalse("fr" in french.audio)
        assertEquals(setOf("en"), SourceLanguages.detect("Movie 2024 English subs").subtitles)
    }

    @Test fun `preferred audio leads, then multi audio, untagged and other languages, quality sorts inside`() {
        val items = listOf(source("Movie.2160p.ITALIAN.mkv", 2160), source("Movie.2160p.mkv", 2160), source("Movie.720p.PORTUGUESE.mkv", 720),
            source("Movie.1080p.MULTI.mkv"), source("Movie.1080p.Dublado.mkv"), source("Movie.480p.ENG.mkv", 480), source("Movie.360p.English.mkv", 360))
        val ordered = SourceLanguageOrder("pt", "").sorted(items, SourceSortOrder()).map { it.title }
        assertEquals(listOf("Movie.1080p.Dublado.mkv", "Movie.720p.PORTUGUESE.mkv", "Movie.1080p.MULTI.mkv", "Movie.2160p.mkv", "Movie.2160p.ITALIAN.mkv", "Movie.480p.ENG.mkv", "Movie.360p.English.mkv"), ordered)
    }

    @Test fun `english treats untagged links as english and subtitles break ties`() {
        val items = listOf(source("Movie.1080p.HINDI.mkv"), source("Movie.1080p.mkv"), source("Movie.1080p.ESub.mkv", detail = "English subs"))
        assertEquals(listOf("Movie.1080p.ESub.mkv", "Movie.1080p.mkv", "Movie.1080p.HINDI.mkv"),
            SourceLanguageOrder("en", "en").sorted(items, SourceSortOrder()).map { it.title })
        assertFalse(SourceLanguageOrder("en", "en").audioMatch(items[1]))
    }

    @Test fun `automatic languages keep the plain quality order`() {
        val items = listOf(source("Movie.720p.PORTUGUESE.mkv", 720), source("Movie.2160p.mkv", 2160))
        assertFalse(SourceLanguageOrder("", "off").active)
        assertEquals(SourceSortOrder().sorted(items), SourceLanguageOrder("", "off").sorted(items, SourceSortOrder()))
    }

    @Test fun `meteor audio and subtitle flag lines are kept apart`() {
        val found = SourceLanguages.detect("Jawan (2023) (1080p BluRay x265 SAMPA).mkv · 📺 1080p | bluray | h265 · 🔊 eac3 5.1 · 🌐 🇮🇳 · 💬 🇬🇧 🇮🇳 🇸🇦 🇳🇱")
        assertEquals(setOf("hi"), found.audio)
        assertEquals(setOf("en", "hi", "ar", "nl"), found.subtitles)
        val subsOnly = SourceLanguages.detect("Oppenheimer.2023.2160p.REMUX.mkv · 🌐 🇬🇧 · 💬 🇬🇧 🇮🇳 🇵🇱")
        assertEquals(setOf("en"), subsOnly.audio)
    }

    @Test fun `untagged links count as the title's original language`() {
        val items = listOf(source("Jawan.2023.2160p.WEBRip.mp4", 2160), source("Jawan.2023.1080p.mkv", detail = "🌐 🇷🇺 🇮🇳"),
            source("Jawan.2023.1080p.Hindi.mkv"), source("Jawan.2023.720p.mkv", 720, "🌐 🇮🇳"), source("Jawan.2023.720p.RUS.mkv", 720))
        val order = SourceLanguageOrder("hi", "")
        assertEquals("hi", order.originalLanguage(items))
        assertEquals(listOf("Jawan.2023.1080p.mkv", "Jawan.2023.1080p.Hindi.mkv", "Jawan.2023.720p.mkv", "Jawan.2023.2160p.WEBRip.mp4", "Jawan.2023.720p.RUS.mkv"),
            order.sorted(items, SourceSortOrder()).map { it.title })
    }

    @Test fun `a long result list sorts quickly`() {
        val items = (0 until 300).map { source("Movie.2023.${listOf(2160, 1080, 720)[it % 3]}p.WEB-DL.DUAL.DDP5.1.H.265-Group$it.mkv", detail =
            "[P2P] Meteor · 📺 1080p | webdl | h265 · 🔊 eac3 5.1 · 🌐 🇬🇧 🇮🇳 · 💬 🇬🇧 🇪🇸 🇫🇷 🇩🇪 🇮🇹 🇵🇱 · 💾 3.2 GiB   👥 12 seeders · 🔗 corpus,bitsearch") }
        SourceLanguageOrder("hi", "en").sorted(items.take(5), SourceSortOrder())
        val started = System.nanoTime()
        val order = SourceLanguageOrder("hi", "en")
        order.sorted(items, SourceSortOrder()).forEach { order.audioMatch(it); order.multiAudio(it); order.subtitleMatch(it) }
        val millis = (System.nanoTime() - started) / 1_000_000
        println("300 links sorted with badges in $millis ms")
        assertTrue("took $millis ms", millis < 3000)
    }

    @Test fun `english fallback lists english links before other languages`() {
        val items = listOf(source("Movie.1080p.ITALIAN.mkv"), source("Movie.1080p.ENG.mkv"), source("Movie.1080p.HINDI.mkv"), source("Movie.720p.ENG.mkv", 720))
        assertEquals(listOf("Movie.1080p.HINDI.mkv", "Movie.1080p.ENG.mkv", "Movie.720p.ENG.mkv", "Movie.1080p.ITALIAN.mkv"),
            SourceLanguageOrder("hi", "", "en").sorted(items, SourceSortOrder()).map { it.title })
        assertEquals("Movie.1080p.ITALIAN.mkv", SourceLanguageOrder("hi", "").sorted(items, SourceSortOrder())[1].title)
    }

    @Test fun `the sort follows the chosen order of language, quality and size`() {
        val items = listOf(
            SourceItem("Movie.2026.1080p.WEB-DL.English.mkv", "fixture", sizeBytes = 3_000_000_000L, qualityHeight = 1080),
            SourceItem("Movie.2026.2160p.WEB-DL.Hindi.mkv", "fixture", sizeBytes = 9_000_000_000L, qualityHeight = 2160),
            SourceItem("Movie.2026.2160p.WEB-DL.English.mkv", "fixture", sizeBytes = 12_000_000_000L, qualityHeight = 2160))
        val english = SourceLanguageOrder("en", "")
        // Language first (default): both English links, 4K before 1080p, then the Hindi one.
        assertEquals(listOf(2, 0, 1), english.sorted(items, SourceSortOrder()).map(items::indexOf))
        // Quality first: the 4K links (English before Hindi), then 1080p.
        assertEquals(listOf(2, 1, 0), english.sorted(items, SourceSortOrder(priority = listOf("quality", "language", "size"))).map(items::indexOf))
        // Size first: largest first by default, smallest first when chosen.
        assertEquals(listOf(2, 1, 0), english.sorted(items, SourceSortOrder(priority = listOf("size", "quality", "language"))).map(items::indexOf))
        assertEquals(listOf(0, 1, 2), english.sorted(items, SourceSortOrder(sizeAscending = true, priority = listOf("size", "quality", "language"))).map(items::indexOf))
        // A saved order is repaired: duplicates dropped, missing keys added.
        assertEquals(listOf("library", "quality", "language", "preferred", "size"), SourceSortOrder.priority("quality,quality,bogus"))
    }
}
