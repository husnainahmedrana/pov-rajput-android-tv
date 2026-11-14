package dev.pov.tv

import dev.pov.tv.data.ApiException

/** Server URLs, playback links and arbitrary exception bodies never become public UI text. */
internal fun uiErrorMessage(error: Exception): String {
    val candidate = when (error) {
        is ApiException, is IllegalStateException, is IllegalArgumentException -> error.message.orEmpty()
        else -> ""
    }
    if (candidate.isBlank() || candidate.length > 700 || Regex("https?://|Bearer\\s|access_token|refresh_token|client_secret|api_key|(?:token|password|client_id)\\s*[:=]", RegexOption.IGNORE_CASE).containsMatchIn(candidate))
        return L10n.tr(R.string.t_this_request_could_not_be_completed, "This request could not be completed. Check your connection and try again.")
    return candidate
}
