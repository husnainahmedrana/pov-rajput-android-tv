package dev.pov.tv

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class TrailerLanguageTest {
    private fun choice(code: String) = TrailerLanguage.CHOICES.first { it.code == code }
    private fun r(id: String, title: String, seconds: Int = 150) = TrailerLanguage.Result(id.padEnd(11, 'x'), title, seconds)

    @Test fun `castilian keeps Spain's dub and skips English, Latin American, subtitled and reaction videos`() {
        val results = listOf(
            r("en", "Gladiator II | Official Trailer (2024 Movie)"),
            r("lat", "ROBOT SALVAJE Tráiler Español Latino (2024)"),
            r("sub", "Breaking Bad Trailer primera temporada Sub- Español"),
            r("react", "Reacción al tráiler en español"),
            r("short", "GLADIATOR 2 Tráiler Español", seconds = 20),
            r("es1", "GLADIATOR 2 Tráiler 2 Español (2024) Denzel Washington"),
            r("es2", "El robot salvaje | Tráiler oficial en castellano"),
        )
        assertEquals(listOf("es1", "es2").map { it.padEnd(11, 'x') }, TrailerLanguage.pick(results, choice("es-ES")))
        assertEquals(listOf("lat").map { it.padEnd(11, 'x') }, TrailerLanguage.pick(results.take(2), choice("es-MX")))
    }

    @Test fun `a results page is read from its initial data, not from every video id on it`() {
        val html = """<script>var ytInitialData = {"contents":{"a":[
            {"adSlotRenderer":{"videoId":"adadadadada"}},
            {"reelShelfRenderer":{"items":[{"videoRenderer":{"videoId":"shortshorts","title":{"runs":[{"text":"x"}]}}}]}},
            {"videoRenderer":{"videoId":"abcdefghijk","title":{"runs":[{"text":"Tráiler "},{"text":"castellano"}]},"lengthText":{"simpleText":"2:05"}}}
            ]}};</script>"""
        assertEquals(listOf(TrailerLanguage.Result("abcdefghijk", "Tráiler castellano", 125)), TrailerLanguage.results(html))
    }
}
