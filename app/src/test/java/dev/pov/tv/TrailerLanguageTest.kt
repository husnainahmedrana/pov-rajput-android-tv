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

    @Test fun `castilian puts Spain's studio channel first and skips Mexico's`() {
        val results = listOf(
            TrailerLanguage.Result("aggregator1", "LA ODISEA Tráiler Oficial Español (2026)", 129, "Trailers In Spanish"),
            TrailerLanguage.Result("mexico00000", "La Odisea | Tráiler Oficial (Universal Pictures)", 145, "Universal Pictures México"),
            TrailerLanguage.Result("spain000000", "LA ODISEA - Tráiler Oficial (Universal Pictures) HD", 145, "Universal Spain"),
            TrailerLanguage.Result("english0000", "The Odyssey | Official New Trailer", 152, "Universal Pictures"),
        )
        assertEquals(listOf("spain000000", "aggregator1"), TrailerLanguage.pick(results, choice("es-ES")))
        assertEquals(listOf("mexico00000", "aggregator1"), TrailerLanguage.pick(results, choice("es-MX")))
    }

    @Test fun `a TMDB video named as another country's dub or a subtitled copy doesn't fit`() {
        val spain = choice("es-ES")
        assertEquals(false, TrailerLanguage.fits("Casino Royale Tráiler Español Latino", spain))
        assertEquals(false, TrailerLanguage.fits("Breaking Bad Trailer Sub- Español", spain))
        assertEquals(true, TrailerLanguage.fits("Casino Royale Trailer Español HD", spain))
    }

    @Test fun `a studio channel's trailer for another title far down the results is not taken`() {
        val results = listOf(
            TrailerLanguage.Result("english0000", "How I Met Your Mother | Trailer", 114, "Henry Page"),
            TrailerLanguage.Result("subtitled00", "How I Met Your Mother - trailer (sub español)", 114, "EdGedeon64"),
            TrailerLanguage.Result("other000000", "Cómo conocí a tu madre trailer HD", 61, "Muviteca"),
            TrailerLanguage.Result("spain000000", "Cómo conocí a vuestra madre (2005 - 2014) [Tráiler ESPAÑOL ESPAÑA]", 54, "Susana López"),
            TrailerLanguage.Result("latam000000", "HIMYM: La película | Tráiler Oficial", 114, "How I Met Your Mother Latinoamérica"),
            TrailerLanguage.Result("spinoff0000", "Trailer Como Conocí a Vuestro Padre en Español", 99, "somosseries"),
            TrailerLanguage.Result("netflix0000", "Hermanastras (EN ESPAÑOL) | Tráiler oficial | Netflix España", 157, "Netflix España"),
            TrailerLanguage.Result("named000000", "How I Met Your Mother: tráiler en castellano", 90, "Someone"),
        )
        assertEquals(listOf("spain000000", "named000000"), TrailerLanguage.pick(results, choice("es-ES"), "How I Met Your Mother"))
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
