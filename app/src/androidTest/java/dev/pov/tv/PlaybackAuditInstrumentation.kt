package dev.pov.tv

import android.app.Activity
import android.app.Instrumentation
import android.app.Dialog
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.Point
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.FocusFinder
import android.view.View
import android.view.ViewGroup
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerControlView
import androidx.media3.ui.PlayerView
import java.util.concurrent.atomic.AtomicReference
import java.io.File
import java.io.FileOutputStream

/**
 * Opt-in real-device audit using only Shaka's public multilingual sample.
 * Invoke with -e test_url <the verified public DASH URL>. No account values are accepted or reported.
 * This is a platform Instrumentation runner; it does not need AndroidX Test or JUnit.
 */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class PlaybackAuditInstrumentation : Instrumentation() {
    private var testUrl = PUBLIC_SAMPLE
    private var sourceFixtureMode = false
    private var miniOnlyMode = false
    private val report = Bundle()
    private var stage = "initializing"
    private var main: MainActivity? = null
    private var fullscreen: PlayerActivity? = null
    private var auditPlayer: ExoPlayer? = null
    private var originalRecent: String? = null
    private var originalBrowseRoot: View? = null
    private var firstMiniPlayerView: PlayerView? = null
    private var auditHomeEdge: View? = null

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        testUrl = arguments?.getString("test_url") ?: PUBLIC_SAMPLE
        sourceFixtureMode = arguments?.getString("audit_mode") == "source_choices"
        miniOnlyMode = arguments?.getString("audit_mode") == "mini_only"
        start()
    }

    override fun onStart() {
        super.onStart()
        var passed = false
        try {
            if (sourceFixtureMode) auditSourceChoices() else audit()
            passed = true
        } catch (failure: Throwable) {
            report.putString("error_stage", stage)
            report.putString("error_class", failure.javaClass.simpleName.take(80))
            val errorCode = try { onMain { InAppPlaybackSession.error?.errorCode ?: 0 } } catch (_: Throwable) { 0 }
            report.putInt("player_error_code", errorCode)
            try { onMain { recordFailureFocus() } } catch (_: Throwable) { /* Numeric focus diagnostics are optional. */ }
            if (stage == "right_edge_enters_visible_mini" || stage.startsWith("home_content_edge")) {
                try { onMain { recordMiniFocus() } } catch (diagnosticFailure: Throwable) {
                    report.putString("mini_diagnostic_error_class", diagnosticFailure.javaClass.simpleName.take(80))
                }
            }
            if (stage == "unified_controller_geometry" && testUrl == PUBLIC_SAMPLE) {
                try {
                    onMain { recordControllerGeometry(playerViewFor(fullscreen!!)) }
                    capturePublicScreenshot("pov-public-player-controls.png")
                    report.putBoolean("public_geometry_failure_screenshot_saved", true)
                } catch (diagnosticFailure: Throwable) {
                    report.putString("geometry_diagnostic_error_class", diagnosticFailure.javaClass.simpleName.take(80))
                }
            }
            if (stage == "audio_bottom_sheet_visible" && testUrl == PUBLIC_SAMPLE) {
                try {
                    onMain { recordSheetGeometry(fullscreen!!.currentOptionSheet!!) }
                    capturePublicScreenshot("pov-public-player-audio.png")
                    report.putBoolean("public_audio_sheet_failure_screenshot_saved", true)
                } catch (diagnosticFailure: Throwable) {
                    report.putString("sheet_diagnostic_error_class", diagnosticFailure.javaClass.simpleName.take(80))
                }
            }
        } finally {
            try {
                onMain {
                    if (InAppPlaybackSession.playerOrNull === auditPlayer && auditPlayer != null) InAppPlaybackSession.stop()
                    fullscreen?.takeUnless { it.isFinishing || it.isDestroyed }?.finish()
                    if (sourceFixtureMode) main?.takeUnless { it.isFinishing || it.isDestroyed }?.finish()
                }
                waitForIdleSync()
                val unchanged = onMain {
                    targetContext.getSharedPreferences("playback", 0).getString("recent", null) == originalRecent
                }
                report.putBoolean("history_unchanged_after_cleanup", unchanged)
                if (!unchanged) passed = false
            } catch (cleanupFailure: Throwable) {
                passed = false
                report.putString("cleanup_error_class", cleanupFailure.javaClass.simpleName.take(80))
            }
            report.putBoolean("passed", passed)
            finish(if (passed) Activity.RESULT_OK else Activity.RESULT_CANCELED, report)
        }
    }

    private fun audit() {
        stage = "public_sample_validation"
        checkAudit(testUrl == PUBLIC_SAMPLE)
        report.putBoolean("verified_public_sample", true)
        stage = "session_idle_precondition"
        originalRecent = onMain { targetContext.getSharedPreferences("playback", 0).getString("recent", null) }
        checkAudit(onMain { !InAppPlaybackSession.hasActive() })
        main = startActivitySync(Intent(targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)) as MainActivity
        waitForIdleSync()
        originalBrowseRoot = onMain { main!!.findViewById<ViewGroup>(android.R.id.content).getChildAt(0) }
        stage = "launch_paused_sample"
        fullscreen = startActivitySync(Intent(targetContext, PlayerActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra("url", testUrl).putExtra("title", "Public playback audit")
            .putExtra("key", "").putExtra("playWhenReady", false)) as PlayerActivity
        waitForIdleSync()
        auditPlayer = onMain { InAppPlaybackSession.playerOrNull }
        checkAudit(auditPlayer != null)
        onMain { auditPlayer!!.volume = 0f }
        await("sample_ready", 30_000) {
            val player = auditPlayer!!
            checkAudit(player.playerError == null)
            player.playbackState == Player.STATE_READY
        }
        report.putBoolean("ready_while_paused", onMain { !auditPlayer!!.playWhenReady })
        checkAudit(report.getBoolean("ready_while_paused"))
        if (miniOnlyMode) {
            auditMiniOnly()
            return
        }

        stage = "supported_tracks"
        val counts = onMain {
            val p = auditPlayer!!
            val audio = PlayerTrackOptions.choices(p.currentTracks, p.trackSelectionParameters, C.TRACK_TYPE_AUDIO)
                .filter { it.kind == TrackChoiceKind.TRACK && it.selectable }
            val text = PlayerTrackOptions.choices(p.currentTracks, p.trackSelectionParameters, C.TRACK_TYPE_TEXT)
                .filter { it.kind == TrackChoiceKind.TRACK && it.selectable }
            audio.size to text.size
        }
        report.putInt("supported_audio_tracks", counts.first)
        report.putInt("supported_subtitle_tracks", counts.second)
        checkAudit(counts.first >= 2 && counts.second >= 1)
        onMain {
            val p = auditPlayer!!
            for ((type, name) in listOf(C.TRACK_TYPE_AUDIO to "audio", C.TRACK_TYPE_TEXT to "subtitle")) {
                val exposed = p.currentTracks.groups.filter { it.type == type }.sumOf { it.length }
                val choices = PlayerTrackOptions.choices(p.currentTracks, p.trackSelectionParameters, type)
                    .filter { it.kind == TrackChoiceKind.TRACK }
                checkAudit(choices.size == exposed)
                report.putInt("${name}_tracks_exposed", exposed)
                report.putInt("${name}_tracks_unavailable", choices.count { !it.selectable })
            }
        }
        report.putBoolean("all_exposed_audio_and_subtitle_tracks_listed", true)

        val audioChoice = onMain {
            val p = auditPlayer!!
            val tracks = PlayerTrackOptions.choices(p.currentTracks, p.trackSelectionParameters, C.TRACK_TYPE_AUDIO)
                .filter { it.kind == TrackChoiceKind.TRACK && it.selectable }
            val language = tracks.first().group!!.getFormat(tracks.first().index).language
            tracks.firstOrNull { it.group!!.getFormat(it.index).language != language } ?: tracks.last()
        }
        onMain {
            val p = auditPlayer!!
            p.trackSelectionParameters = PlayerTrackOptions.apply(p.trackSelectionParameters, audioChoice)
        }
        await("audio_override_selected", 12_000) {
            auditPlayer!!.currentTracks.groups.any {
                it.mediaTrackGroup == audioChoice.group && it.isTrackSelected(audioChoice.index)
            }
        }
        report.putBoolean("audio_track_switched", true)
        checkAudit(onMain {
            val p = auditPlayer!!
            PlayerTrackOptions.choices(p.currentTracks, p.trackSelectionParameters, C.TRACK_TYPE_AUDIO).any {
                it.group == audioChoice.group && it.index == audioChoice.index && it.selected && it.checked
            }
        })
        report.putBoolean("manual_audio_choice_matches_actual_selected_track", true)

        val subtitleChoice = onMain {
            val p = auditPlayer!!
            PlayerTrackOptions.choices(p.currentTracks, p.trackSelectionParameters, C.TRACK_TYPE_TEXT)
                .first { it.kind == TrackChoiceKind.TRACK && it.selectable }
        }
        onMain {
            val p = auditPlayer!!
            p.trackSelectionParameters = PlayerTrackOptions.apply(p.trackSelectionParameters, subtitleChoice)
        }
        await("subtitle_override_selected", 12_000) {
            auditPlayer!!.currentTracks.groups.any {
                it.mediaTrackGroup == subtitleChoice.group && it.isTrackSelected(subtitleChoice.index)
            }
        }
        report.putBoolean("subtitle_track_selected", true)
        stage = "subtitle_off_and_automatic"
        onMain {
            val p = auditPlayer!!
            val off = PlayerTrackOptions.choices(p.currentTracks, p.trackSelectionParameters, C.TRACK_TYPE_TEXT)
                .first { it.kind == TrackChoiceKind.OFF }
            p.trackSelectionParameters = PlayerTrackOptions.apply(p.trackSelectionParameters, off)
        }
        await("subtitle_off", 5_000) {
            val p = auditPlayer!!
            C.TRACK_TYPE_TEXT in p.trackSelectionParameters.disabledTrackTypes &&
                p.currentTracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }.none { it.isSelected }
        }
        report.putBoolean("subtitles_off", true)
        onMain {
            val p = auditPlayer!!
            for (type in listOf(C.TRACK_TYPE_AUDIO, C.TRACK_TYPE_TEXT)) {
                val automatic = PlayerTrackOptions.choices(p.currentTracks, p.trackSelectionParameters, type)
                    .first { it.kind == TrackChoiceKind.AUTOMATIC }
                p.trackSelectionParameters = PlayerTrackOptions.apply(p.trackSelectionParameters, automatic)
            }
            checkAudit(C.TRACK_TYPE_TEXT !in p.trackSelectionParameters.disabledTrackTypes)
            checkAudit(p.trackSelectionParameters.overrides.values.none {
                it.type == C.TRACK_TYPE_AUDIO || it.type == C.TRACK_TYPE_TEXT
            })
        }
        report.putBoolean("audio_and_subtitles_automatic_restored", true)
        checkAudit(onMain {
            val p = auditPlayer!!
            val choices = PlayerTrackOptions.choices(p.currentTracks, p.trackSelectionParameters, C.TRACK_TYPE_AUDIO)
            choices.first().checked && choices.any { it.kind == TrackChoiceKind.TRACK && it.selected && !it.checked }
        })
        report.putBoolean("automatic_mode_separate_from_renderer_selected_tracks", true)

        onMain { auditPlayer!!.play() }
        await("muted_playback_started", 15_000) { auditPlayer!!.isPlaying }
        report.putBoolean("muted_playback_started", true)
        stage = "volume_boost_request_and_reset"
        val framesBeforeBoost = onMain { InAppPlaybackSession.volumeBoost.processedPcmFrames }
        val boostState = onMain { InAppPlaybackSession.setVolumeBoostGain(3) }
        report.putBoolean("boost_initial_waiting_for_audio", boostState == VolumeBoostState.WAITING_FOR_AUDIO)
        await("software_boost_pcm_processed", 5_000) {
            InAppPlaybackSession.volumeBoost.state == VolumeBoostState.REQUESTED &&
                InAppPlaybackSession.volumeBoost.processedPcmFrames > framesBeforeBoost
        }
        report.putBoolean("boost_pcm_processed", true)
        report.putBoolean("boost_effect_requested", true)
        report.putLong("boost_processed_pcm_frame_delta", onMain {
            InAppPlaybackSession.volumeBoost.processedPcmFrames - framesBeforeBoost
        })
        onMain {
            InAppPlaybackSession.setVolumeBoostGain(0)
            checkAudit(InAppPlaybackSession.volumeBoost.state == VolumeBoostState.OFF)
            checkAudit(InAppPlaybackSession.volumeBoost.gainDb == 0)
        }
        report.putBoolean("boost_reset", true)

        val playerView = onMain { requireNotNull(fullscreen!!.findViewById<ViewGroup>(android.R.id.content)
            .findViewWithTag<PlayerView>("pov_fullscreen_player")) }
        stage = "unified_controller_geometry"
        onMain { playerView.controllerShowTimeoutMs = 4_000 }
        revealControls(fullscreen!!)
        await("unified_controller_geometry", 3_000) { validControllerGeometry(playerView) }
        report.putBoolean("all_actions_in_bottom_controller_bounds", true)
        report.putBoolean("compact_controller_height_within_120_to_135_dp", true)
        report.putInt("compact_controller_height_dp", onMain {
            (playerView.findViewById<View>(R.id.pov_bottom_scrim).height / targetContext.resources.displayMetrics.density).toInt()
        })
        capturePublicScreenshot("pov-public-player-controls.png")
        report.putBoolean("public_geometry_screenshot_saved", true)
        report.putBoolean("independent_buffering_spinner_hidden", onMain {
            !rendered(playerView.findViewById(androidx.media3.ui.R.id.exo_buffering))
        })
        stage = "audio_picker_remote_navigation"
        onMain { playerView.findViewById<View>(androidx.media3.ui.R.id.exo_play_pause).requestFocus() }
        press(fullscreen!!, KeyEvent.KEYCODE_DPAD_RIGHT)
        press(fullscreen!!, KeyEvent.KEYCODE_DPAD_RIGHT)
        checkAudit(onMain { fullscreen!!.currentFocus?.id == R.id.pov_audio })
        press(fullscreen!!, KeyEvent.KEYCODE_DPAD_CENTER)
        await("audio_bottom_sheet_visible", 3_000) {
            fullscreen!!.currentOptionSheet?.isShowing == true && validSheetGeometry(fullscreen!!.currentOptionSheet!!)
        }
        report.putBoolean("audio_picker_reachable_by_dpad", true)
        capturePublicScreenshot("pov-public-player-audio.png")
        traverseAudioSheet(fullscreen!!.currentOptionSheet!!)
        closeSheet(fullscreen!!.currentOptionSheet!!)
        stage = "speed_picker_remote_navigation"
        onMain { playerView.findViewById<View>(R.id.pov_audio).requestFocus() }
        press(fullscreen!!, KeyEvent.KEYCODE_DPAD_RIGHT)
        checkAudit(onMain { fullscreen!!.currentFocus?.id == R.id.pov_speed })
        press(fullscreen!!, KeyEvent.KEYCODE_DPAD_CENTER)
        await("speed_bottom_sheet_visible", 3_000) { fullscreen!!.currentOptionSheet?.isShowing == true }
        closeSheet(fullscreen!!.currentOptionSheet!!)
        report.putBoolean("speed_picker_reachable_by_dpad", true)
        revealControls(fullscreen!!)
        onMain { playerView.findViewById<View>(R.id.pov_speed).requestFocus() }
        SystemClock.sleep(300)
        capturePublicScreenshot("pov-public-player-controls.png")
        report.putBoolean("public_controls_screenshot_saved", true)
        stage = "manual_controls_hide"
        onMain { playerView.hideController(); checkAudit(allControlsHidden(playerView)) }
        report.putBoolean("manual_hide_includes_timeline_loading_and_actions", true)
        stage = "timed_controls_hide_with_action_focused"
        onMain { playerView.controllerShowTimeoutMs = 800 }
        revealControls(fullscreen!!)
        onMain {
            playerView.findViewById<View>(R.id.pov_audio).requestFocus()
            checkAudit(fullscreen!!.currentFocus?.id == R.id.pov_audio)
        }
        await("timed_controls_hide_with_action_focused", 3_000) { allControlsHidden(playerView) }
        report.putBoolean("focused_audio_does_not_pin_controls", true)
        report.putBoolean("timeout_hides_timeline_loading_and_actions", true)
        SystemClock.sleep(250)
        capturePublicScreenshot("pov-public-player-hidden.png")
        report.putBoolean("public_hidden_screenshot_saved", true)
        stage = "dpad_restores_controls"
        press(fullscreen!!, KeyEvent.KEYCODE_DPAD_UP)
        checkAudit(onMain { validControllerGeometry(playerView) })
        report.putBoolean("dpad_restores_complete_controls", true)

        stage = "browse_handoff"
        val beforeBrowse = onMain { auditPlayer!!.currentPosition }
        val framesBeforeBrowse = onMain { renderedVideoFrames() }
        onMain { fullscreen!!.onBackPressed() }
        waitForIdleSync()
        await("mini_view_attached_same_player", 5_000) {
            InAppPlaybackSession.playerOrNull === auditPlayer &&
                descendants(main!!.findViewById(android.R.id.content)).filterIsInstance<PlayerView>()
                    .any { it.player === auditPlayer }
        }
        checkAudit(onMain { main!!.findViewById<ViewGroup>(android.R.id.content).getChildAt(0) === originalBrowseRoot })
        report.putBoolean("browse_root_retained_after_fullscreen", true)
        firstMiniPlayerView = onMain {
            descendants(main!!.findViewById(android.R.id.content)).filterIsInstance<PlayerView>().first { it.player === auditPlayer }
        }
        await("browse_playback_advances", 7_000) {
            auditPlayer!!.isPlaying && auditPlayer!!.currentPosition > beforeBrowse + 400
        }
        report.putBoolean("browse_uses_same_player", true)
        report.putBoolean("position_advances_while_browsing", true)
        verifyPublicVideoMotion("mini", main!!, firstMiniPlayerView!!, true, framesBeforeBrowse)
        val framesBeforeReturn = onMain { renderedVideoFrames() }
        fullscreen = returnToFullscreen()
        stage = "return_position_preserved"
        checkAudit(onMain {
            InAppPlaybackSession.playerOrNull === auditPlayer &&
                auditPlayer!!.currentPosition >= beforeBrowse && playerViewFor(fullscreen!!).player === auditPlayer
        })
        report.putBoolean("return_uses_same_player_without_reset", true)
        verifyPublicVideoMotion("fullscreen", fullscreen!!, playerViewFor(fullscreen!!), false, framesBeforeReturn)

        stage = "paused_handoff"
        onMain { auditPlayer!!.pause() }
        revealControls(fullscreen!!)
        val immediatePausePosition = onMain { auditPlayer!!.currentPosition }
        report.putBoolean("pause_request_applied", onMain { !auditPlayer!!.playWhenReady })
        // pause() changes application state before the playback thread/audio clock acknowledges it.
        // Compare the handoff with an acknowledged, stable paused clock, not the pre-ack snapshot.
        val pausedAt = awaitPausedClock(auditPlayer!!)
        report.putLong("pause_settle_position_delta_ms", pausedAt - immediatePausePosition)
        stage = "paused_browse_handoff"
        onMain { fullscreen!!.onBackPressed() }
        waitForIdleSync()
        await("paused_mini_view_attached", 5_000) {
            descendants(main!!.findViewById(android.R.id.content)).filterIsInstance<PlayerView>()
                .any { it.player === auditPlayer }
        }
        checkAudit(onMain {
            main!!.findViewById<ViewGroup>(android.R.id.content).getChildAt(0) === originalBrowseRoot &&
                firstMiniPlayerView?.player === auditPlayer &&
                descendants(main!!.findViewById(android.R.id.content)).any { it === firstMiniPlayerView }
        })
        report.putBoolean("browse_root_retained_across_repeat_handoffs", true)
        report.putBoolean("existing_mini_player_view_reused", true)
        SystemClock.sleep(700)
        stage = "paused_browse_handoff"
        val browsePaused = onMain { !auditPlayer!!.playWhenReady && !auditPlayer!!.isPlaying }
        val browseSamePlayer = onMain { InAppPlaybackSession.playerOrNull === auditPlayer }
        val browsePauseDelta = onMain { auditPlayer!!.currentPosition - pausedAt }
        report.putBoolean("paused_browse_still_paused", browsePaused)
        report.putBoolean("paused_browse_same_player", browseSamePlayer)
        report.putLong("paused_browse_position_delta_ms", browsePauseDelta)
        checkAudit(browsePaused && browseSamePlayer && kotlin.math.abs(browsePauseDelta) < 250)
        fullscreen = returnToFullscreen()
        stage = "paused_fullscreen_handoff"
        val fullscreenPaused = onMain { !auditPlayer!!.playWhenReady && !auditPlayer!!.isPlaying }
        val fullscreenSamePlayer = onMain { InAppPlaybackSession.playerOrNull === auditPlayer }
        val fullscreenPauseDelta = onMain { auditPlayer!!.currentPosition - pausedAt }
        report.putBoolean("paused_fullscreen_still_paused", fullscreenPaused)
        report.putBoolean("paused_fullscreen_same_player", fullscreenSamePlayer)
        report.putLong("paused_fullscreen_position_delta_ms", fullscreenPauseDelta)
        checkAudit(fullscreenPaused && fullscreenSamePlayer && kotlin.math.abs(fullscreenPauseDelta) < 250)
        report.putBoolean("paused_handoff_preserves_pause_and_position", true)
        stage = "paused_controls_remain_visible"
        revealControls(fullscreen!!)
        SystemClock.sleep(1_200)
        checkAudit(onMain { playerViewFor(fullscreen!!).isControllerFullyVisible })
        report.putBoolean("paused_controls_remain_visible", true)
        stage = "explicit_stop"
        onMain { InAppPlaybackSession.stop() }
        waitForIdleSync()
        checkAudit(onMain { !InAppPlaybackSession.hasActive() && InAppPlaybackSession.playerOrNull == null })
        report.putBoolean("stop_releases_session", true)
        stage = "history_preserved"
        checkAudit(onMain { targetContext.getSharedPreferences("playback", 0).getString("recent", null) == originalRecent })
        report.putBoolean("history_unchanged", true)
    }

    /** Offline production-row fixture: no service request, media link, or account/library label. */
    private fun auditSourceChoices() {
        stage = "offline_source_fixture_precondition"
        originalRecent = onMain { targetContext.getSharedPreferences("playback", 0).getString("recent", null) }
        checkAudit(onMain { !InAppPlaybackSession.hasActive() })
        main = startActivitySync(Intent(targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra("source_choice_preview", true)) as MainActivity
        waitForIdleSync()
        await("offline_source_rows_available", 5_000) { fixtureRows("source:").size == 7 }
        report.putBoolean("offline_synthetic_source_fixture", true)
        report.putInt("source_choice_rows", 7)
        val first = onMain { fixtureRows("source:").first() }
        onMain { checkAudit(first.requestFocus()) }
        assertFocusedFixtureRow(first, "source_first_row_visible")
        for (index in 1..6) {
            press(main!!, KeyEvent.KEYCODE_DPAD_DOWN)
            val row = onMain { fixtureRows("source:")[index] }
            assertFocusedFixtureRow(row, "source_down_${index}_visible")
        }
        for (index in 5 downTo 0) {
            press(main!!, KeyEvent.KEYCODE_DPAD_UP)
            val row = onMain { fixtureRows("source:")[index] }
            assertFocusedFixtureRow(row, "source_up_${index}_visible")
        }
        report.putBoolean("all_source_rows_reachable_in_both_directions", true)
        var fileSelections = 0
        for (sourceIndex in 1..5) {
            press(main!!, KeyEvent.KEYCODE_DPAD_DOWN)
            val source = onMain { fixtureRows("source:")[sourceIndex] }
            assertFocusedFixtureRow(source, "source_middle_${sourceIndex}_focused")
            val key = onMain { source.tag as String }
            press(main!!, KeyEvent.KEYCODE_DPAD_CENTER)
            await("fixture_file_rows_available", 5_000) { fixtureRows("file:torrents:1234:").size == 7 }
            val files = onMain { fixtureRows("file:torrents:1234:") }
            onMain { checkAudit(files.first().requestFocus()) }
            assertFocusedFixtureRow(files.first(), "file_first_row_visible")
            for (fileIndex in 0..6) {
                if (fileIndex > 0) press(main!!, KeyEvent.KEYCODE_DPAD_DOWN)
                assertFocusedFixtureRow(files[fileIndex], "file_down_${fileIndex}_visible")
                if (fileIndex in 1..5) {
                    press(main!!, KeyEvent.KEYCODE_DPAD_CENTER)
                    await("file_middle_${fileIndex}_exact_selection", 2_000) {
                        val selection = main!!.findViewById<ViewGroup>(android.R.id.content)
                            .findViewWithTag<android.widget.TextView>("fixture:selection")
                        selection?.text?.toString() == "Selected file version ${fileIndex + 1}" &&
                            main!!.currentFocus === files[fileIndex]
                    }
                    fileSelections++
                }
            }
            for (fileIndex in 5 downTo 0) {
                press(main!!, KeyEvent.KEYCODE_DPAD_UP)
                assertFocusedFixtureRow(files[fileIndex], "file_up_${fileIndex}_visible")
            }
            press(main!!, KeyEvent.KEYCODE_BACK)
            await("back_restores_exact_source_choice", 3_000) {
                val focused = main!!.currentFocus
                focused is TvChoiceRow && focused.tag == key && fullyRendered(focused)
            }
        }
        report.putInt("middle_sources_opened_by_dpad", 5)
        report.putInt("middle_files_selected_by_dpad", fileSelections)
        report.putBoolean("all_file_rows_reachable_in_both_directions", true)
        report.putBoolean("all_focused_source_file_cards_fully_visible", true)
        report.putBoolean("back_restores_exact_source_choice", true)
        report.putBoolean("source_file_children_do_not_steal_focus", true)
    }

    private fun auditMiniOnly() {
        report.putBoolean("mini_only_mode", true)
        onMain { auditPlayer!!.play() }
        await("muted_playback_started", 15_000) { auditPlayer!!.isPlaying }
        val beforeBrowse = onMain { auditPlayer!!.currentPosition }
        val framesBeforeBrowse = onMain { renderedVideoFrames() }
        onMain { fullscreen!!.onBackPressed() }
        waitForIdleSync()
        await("mini_view_attached_same_player", 5_000) {
            InAppPlaybackSession.playerOrNull === auditPlayer &&
                descendants(main!!.findViewById(android.R.id.content)).filterIsInstance<PlayerView>().any { it.player === auditPlayer }
        }
        checkAudit(onMain { main!!.findViewById<ViewGroup>(android.R.id.content).getChildAt(0) === originalBrowseRoot })
        report.putBoolean("browse_root_retained_after_fullscreen", true)
        firstMiniPlayerView = onMain {
            descendants(main!!.findViewById(android.R.id.content)).filterIsInstance<PlayerView>().first { it.player === auditPlayer }
        }
        await("browse_playback_advances", 7_000) { auditPlayer!!.isPlaying && auditPlayer!!.currentPosition > beforeBrowse + 400 }
        report.putBoolean("position_advances_while_browsing", true)
        verifyPublicVideoMotion("mini", main!!, firstMiniPlayerView!!, true, framesBeforeBrowse)
        val framesBeforeReturn = onMain { renderedVideoFrames() }
        fullscreen = returnToFullscreen()
        checkAudit(onMain { InAppPlaybackSession.playerOrNull === auditPlayer &&
            playerViewFor(fullscreen!!).player === auditPlayer && auditPlayer!!.currentPosition >= beforeBrowse })
        report.putBoolean("return_uses_same_player_without_reset", true)
        verifyPublicVideoMotion("fullscreen", fullscreen!!, playerViewFor(fullscreen!!), false, framesBeforeReturn)
        val framesBeforeRepeatBrowse = onMain { renderedVideoFrames() }
        onMain { fullscreen!!.onBackPressed() }
        waitForIdleSync()
        await("repeat_mini_reuses_view", 5_000) {
            firstMiniPlayerView?.player === auditPlayer &&
                main!!.findViewById<ViewGroup>(android.R.id.content).getChildAt(0) === originalBrowseRoot
        }
        report.putBoolean("existing_mini_player_view_reused", true)
        verifyPublicVideoMotion("repeat_mini", main!!, firstMiniPlayerView!!, true, framesBeforeRepeatBrowse)
        onMain { InAppPlaybackSession.stop() }
        checkAudit(onMain { !InAppPlaybackSession.hasActive() })
        report.putBoolean("stop_releases_session", true)
    }

    private fun fixtureRows(prefix: String): List<TvChoiceRow> =
        descendants(main!!.findViewById(android.R.id.content)).filterIsInstance<TvChoiceRow>()
            .filter { (it.tag as? String)?.startsWith(prefix) == true }

    private fun assertFocusedFixtureRow(row: TvChoiceRow, name: String) {
        await(name, 3_000) {
            main!!.currentFocus === row && row.hasFocus() && fullyRendered(row) &&
                row.descendantFocusability == ViewGroup.FOCUS_BLOCK_DESCENDANTS
        }
    }

    private fun returnToFullscreen(): PlayerActivity {
        stage = "main_home_to_mini"
        enterMiniFromHomeEdge()
        stage = "mini_menu_key"
        press(main!!, KeyEvent.KEYCODE_MENU)
        await("mini_menu_dialog", 3_000) {
            main!!.currentPlaybackOptionsDialog?.let {
                it.isShowing && it.window?.decorView?.tag == "pov_mini_playback_options"
            } == true
        }
        report.putBoolean("mini_menu_opens_playback_options", true)
        closeSheet(onMain { main!!.currentPlaybackOptionsDialog!! })
        stage = "mini_hold_select_fallback"
        onMain {
            val mini = main!!.findViewById<ViewGroup>(android.R.id.content).findViewWithTag<View>("return-to-video")
            mini.requestFocus()
            checkAudit(mini.performLongClick())
        }
        await("mini_hold_select_dialog", 3_000) { main!!.currentPlaybackOptionsDialog?.isShowing == true }
        closeSheet(onMain { main!!.currentPlaybackOptionsDialog!! })
        report.putBoolean("mini_hold_select_opens_playback_options", true)
        stage = "mini_center_returns_fullscreen"
        val monitor = addMonitor(PlayerActivity::class.java.name, null, false)
        try {
            onMain { main!!.findViewById<ViewGroup>(android.R.id.content).findViewWithTag<View>("return-to-video").requestFocus() }
            press(main!!, KeyEvent.KEYCODE_DPAD_CENTER)
            val result = waitForMonitorWithTimeout(monitor, 5_000) as? PlayerActivity
            checkAudit(result != null)
            waitForIdleSync()
            report.putBoolean("mini_center_returns_fullscreen", true)
            return result!!
        } finally { removeMonitor(monitor) }
    }

    private fun enterMiniFromHomeEdge() {
        onMain {
            val home = main!!.findViewById<ViewGroup>(android.R.id.content).findViewWithTag<View>("nav:home")
            checkAudit(home != null)
            home.requestFocus(); home.performClick()
        }
        waitForIdleSync()
        var edge: View? = null
        await("home_content_edge_available", 15_000) {
            val field = MainActivity::class.java.getDeclaredField("body").apply { isAccessible = true }
            val body = field.get(main) as ViewGroup
            edge = body.getFocusables(View.FOCUS_FORWARD).lastOrNull { candidate ->
                val next = FocusFinder.getInstance().findNextFocus(body, candidate, View.FOCUS_RIGHT)
                candidate !== body && candidate.isShown && candidate.isEnabled && candidate.isFocusable &&
                    (next == null || next === candidate || next === body)
            }
            auditHomeEdge = edge
            edge != null
        }
        onMain { checkAudit(edge!!.requestFocus()) }
        waitForIdleSync()
        await("home_content_edge_focused_visible", 3_000) { main!!.currentFocus === edge && fullyRendered(edge!!) }
        press(main!!, KeyEvent.KEYCODE_DPAD_RIGHT)
        await("right_edge_enters_visible_mini", 3_000) {
            val mini = main!!.findViewById<ViewGroup>(android.R.id.content).findViewWithTag<ViewGroup>("return-to-video")
            mini != null && mini.hasFocus() && rendered(mini) && mini.descendantFocusability == ViewGroup.FOCUS_BLOCK_DESCENDANTS
        }
        report.putBoolean("home_right_edge_enters_focused_mini", true)
    }

    private fun playerViewFor(activity: PlayerActivity): PlayerView = requireNotNull(
        activity.findViewById<ViewGroup>(android.R.id.content).findViewWithTag<PlayerView>("pov_fullscreen_player"))

    private fun traverseAudioSheet(dialog: Dialog) {
        val rows = onMain {
            descendants(dialog.window!!.decorView).filter { view ->
                // The Audio column of the Audio & Subtitles sheet.
                (view.tag as? String)?.startsWith("pov_sheet_choice_0_") == true
            }.sortedBy { (it.tag as String).removePrefix("pov_sheet_choice_0_").toInt() }
        }
        checkAudit(rows.size >= 3)
        val initiallyOffscreen = onMain { !fullyRendered(rows.last()) }
        checkAudit(initiallyOffscreen)
        onMain { checkAudit(rows.first().requestFocus()) }
        await("audio_sheet_first_row_focused_visible", 3_000) {
            dialog.currentFocus === rows.first() && fullyRendered(rows.first())
        }
        for (index in 1 until rows.size) {
            press(dialog, KeyEvent.KEYCODE_DPAD_DOWN)
            await("audio_sheet_row_${index}_focused_visible", 3_000) {
                dialog.currentFocus === rows[index] && fullyRendered(rows[index])
            }
        }
        report.putInt("audio_sheet_rows_reached_by_dpad", rows.size)
        report.putBoolean("audio_sheet_last_row_initially_offscreen", initiallyOffscreen)
        report.putBoolean("audio_sheet_all_focused_rows_fully_visible", true)
        report.putBoolean("audio_sheet_last_choice_reached_by_dpad", true)
    }

    private val chromeIds = listOf(androidx.media3.ui.R.id.exo_progress, androidx.media3.ui.R.id.exo_play_pause,
        androidx.media3.ui.R.id.exo_rew, androidx.media3.ui.R.id.exo_ffwd, R.id.pov_audio,
        R.id.pov_speed, R.id.pov_browse)

    private fun validControllerGeometry(playerView: PlayerView): Boolean {
        val bottom = playerView.findViewById<View>(R.id.pov_bottom_scrim)
        val screenBounds = Rect()
        val bottomBounds = Rect()
        if (!playerView.getGlobalVisibleRect(screenBounds) || !bottom.getGlobalVisibleRect(bottomBounds)) return false
        if (bottomBounds.top < screenBounds.top + screenBounds.height() / 2 || !screenBounds.contains(bottomBounds)) return false
        val heightDp = bottomBounds.height() / playerView.resources.displayMetrics.density
        if (heightDp !in 120f..135f) return false
        return chromeIds.all { id ->
            val child = playerView.findViewById<View>(id)
            val bounds = Rect()
            rendered(child) && child.getGlobalVisibleRect(bounds) && bottomBounds.contains(bounds) && insideController(child, playerView)
        }
    }

    /** Fixed view IDs and numeric geometry only: never labels, titles, URLs, or account state. */
    private fun recordControllerGeometry(playerView: PlayerView) {
        val metrics = targetContext.resources.displayMetrics
        report.putInt("geometry_display_density_dpi", metrics.densityDpi)
        report.putInt("geometry_display_width_px", metrics.widthPixels)
        report.putInt("geometry_display_height_px", metrics.heightPixels)
        report.putInt("geometry_font_scale_percent", (targetContext.resources.configuration.fontScale * 100).toInt())
        val namedViews = listOf(
            "player" to playerView,
            "controller" to playerView.findViewById<View>(androidx.media3.ui.R.id.exo_controller),
            "scrim" to playerView.findViewById<View>(R.id.pov_bottom_scrim),
            "actions" to playerView.findViewById<View>(R.id.pov_player_actions),
            "timeline" to playerView.findViewById<View>(androidx.media3.ui.R.id.exo_progress),
            "play_pause" to playerView.findViewById<View>(androidx.media3.ui.R.id.exo_play_pause),
            "rewind" to playerView.findViewById<View>(androidx.media3.ui.R.id.exo_rew),
            "forward" to playerView.findViewById<View>(androidx.media3.ui.R.id.exo_ffwd),
            "audio" to playerView.findViewById<View>(R.id.pov_audio),
            "speed" to playerView.findViewById<View>(R.id.pov_speed),
            "browse" to playerView.findViewById<View>(R.id.pov_browse)
        )
        namedViews.forEach { (name, view) ->
            if (view == null) {
                report.putBoolean("geometry_${name}_missing", true)
            } else {
                val visibleBounds = Rect()
                val hasVisibleBounds = view.getGlobalVisibleRect(visibleBounds)
                val location = IntArray(2)
                view.getLocationOnScreen(location)
                report.putString("geometry_${name}_bounds", visibleBounds.flattenToString())
                report.putString("geometry_${name}_layout", "${location[0]} ${location[1]} ${view.width} ${view.height}")
                report.putString("geometry_${name}_padding", "${view.paddingLeft} ${view.paddingTop} ${view.paddingRight} ${view.paddingBottom}")
                report.putInt("geometry_${name}_visibility", view.visibility)
                report.putBoolean("geometry_${name}_shown", view.isShown)
                report.putBoolean("geometry_${name}_has_visible_bounds", hasVisibleBounds)
                report.putBoolean("geometry_${name}_inside_controller", insideController(view, playerView))
            }
        }
    }

    private fun validSheetGeometry(dialog: Dialog): Boolean {
        val content = dialog.window?.decorView?.findViewWithTag<View>("pov_player_sheet") ?: return false
        val bounds = visibleScreenBounds(content) ?: return false
        val display = targetContext.resources.displayMetrics.let { Rect(0, 0, it.widthPixels, it.heightPixels) }
        return fullyRendered(content) && !bounds.isEmpty && display.contains(bounds) &&
            bounds.bottom > display.height() * 0.7
    }

    private fun recordSheetGeometry(dialog: Dialog) {
        report.putBoolean("sheet_dialog_showing", dialog.isShowing)
        report.putInt("sheet_display_width_px", targetContext.resources.displayMetrics.widthPixels)
        report.putInt("sheet_display_height_px", targetContext.resources.displayMetrics.heightPixels)
        val window = dialog.window
        report.putBoolean("sheet_window_missing", window == null)
        window?.attributes?.let {
            report.putInt("sheet_window_gravity", it.gravity)
            report.putInt("sheet_window_width", it.width)
            report.putInt("sheet_window_height", it.height)
            report.putInt("sheet_window_x", it.x)
            report.putInt("sheet_window_y", it.y)
        }
        val decor = window?.decorView
        val content = decor?.findViewWithTag<View>("pov_player_sheet")
        for ((name, view) in listOf("decor" to decor, "content" to content, "focus" to dialog.currentFocus)) {
            report.putBoolean("sheet_${name}_missing", view == null)
            if (view != null) {
                val bounds = Rect()
                val hasBounds = view.getGlobalVisibleRect(bounds)
                val location = IntArray(2)
                view.getLocationOnScreen(location)
                report.putString("sheet_${name}_bounds", bounds.flattenToString())
                report.putString("sheet_${name}_screen_bounds", visibleScreenBounds(view)?.flattenToString() ?: "none")
                report.putString("sheet_${name}_layout", "${location[0]} ${location[1]} ${view.width} ${view.height}")
                report.putInt("sheet_${name}_visibility", view.visibility)
                report.putBoolean("sheet_${name}_shown", view.isShown)
                report.putBoolean("sheet_${name}_has_bounds", hasBounds)
                report.putBoolean("sheet_${name}_window_focus", view.hasWindowFocus())
            }
        }
    }

    private fun insideController(child: View, playerView: PlayerView): Boolean {
        val controller = playerView.findViewById<PlayerControlView>(androidx.media3.ui.R.id.exo_controller)
        var parent = child.parent
        while (parent is View && parent !== controller) parent = parent.parent
        return parent === controller
    }

    private fun allControlsHidden(playerView: PlayerView): Boolean =
        playerView.findViewById<PlayerControlView>(androidx.media3.ui.R.id.exo_controller).visibility == View.GONE &&
            chromeIds.all { !rendered(playerView.findViewById(it)) } &&
            !rendered(playerView.findViewById(androidx.media3.ui.R.id.exo_buffering))

    private fun rendered(view: View): Boolean {
        val bounds = Rect()
        return view.isShown && view.alpha > 0 && view.getGlobalVisibleRect(bounds) && !bounds.isEmpty
    }

    private fun fullyRendered(view: View): Boolean {
        val visible = visibleScreenBounds(view) ?: return false
        val location = IntArray(2)
        if (!rendered(view)) return false
        view.getLocationOnScreen(location)
        return visible.contains(Rect(location[0], location[1], location[0] + view.width, location[1] + view.height))
    }

    private fun visibleScreenBounds(view: View): Rect? {
        val visible = Rect()
        val rootOffset = Point()
        if (!view.getGlobalVisibleRect(visible, rootOffset)) return null
        val screenOrigin = IntArray(2)
        view.getLocationOnScreen(screenOrigin)
        // getGlobalVisibleRect is relative to the view root, which starts at a dialog's window
        // origin. Translate its already-clipped rectangle into screen coordinates before comparison.
        visible.offset(screenOrigin[0] - rootOffset.x - view.scrollX,
            screenOrigin[1] - rootOffset.y - view.scrollY)
        return visible
    }

    private fun press(activity: Activity, key: Int) {
        checkAudit(onMain { !activity.isFinishing && !activity.isDestroyed })
        val previousStage = stage
        await("activity_window_has_input_focus", 1_500) { activity.hasWindowFocus() }
        stage = previousStage
        injectKey(key)
    }

    private fun revealControls(activity: PlayerActivity) {
        // Exercise the same reveal/interaction path as a TV remote. Calling PlayerView.showController()
        // directly bypasses the app's last-user-interaction deadline and can immediately hide chrome.
        press(activity, KeyEvent.KEYCODE_DPAD_UP)
        await("remote_controls_visible", 1_000) { playerViewFor(activity).isControllerFullyVisible }
    }

    private fun press(dialog: Dialog, key: Int) {
        checkAudit(onMain { dialog.isShowing })
        val previousStage = stage
        await("dialog_window_has_input_focus", 1_500) { dialog.window?.decorView?.hasWindowFocus() == true }
        stage = previousStage
        injectKey(key)
    }

    private fun injectKey(key: Int) {
        // Direct Activity/Dialog.dispatchKeyEvent bypasses ViewRootImpl's unhandled directional
        // navigation. Inject into the actual focused window so DPAD tests exercise native focus.
        val downAt = SystemClock.uptimeMillis()
        fun event(action: Int) = KeyEvent(downAt, SystemClock.uptimeMillis(), action, key, 0, 0,
            KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_DPAD)
        checkAudit(uiAutomation.injectInputEvent(event(KeyEvent.ACTION_DOWN), true))
        checkAudit(uiAutomation.injectInputEvent(event(KeyEvent.ACTION_UP), true))
        waitForIdleSync()
    }

    private fun closeSheet(dialog: Dialog) {
        press(dialog, KeyEvent.KEYCODE_BACK)
        checkAudit(onMain { !dialog.isShowing })
    }

    private fun recordFailureFocus() {
        val activity = fullscreen?.takeUnless { it.isDestroyed || it.isFinishing } ?: main
        val dialog = fullscreen?.currentOptionSheet ?: main?.currentPlaybackOptionsDialog
        fun record(prefix: String, focused: View?) {
            val id = focused?.id ?: View.NO_ID
            report.putInt("${prefix}_resource_id", id)
            report.putString("${prefix}_view_class", focused?.javaClass?.simpleName ?: "none")
            if (id != View.NO_ID) {
                try { report.putString("${prefix}_resource_name", targetContext.resources.getResourceEntryName(id)) }
                catch (_: android.content.res.Resources.NotFoundException) { /* Dynamically assigned IDs have no resource name. */ }
            }
        }
        record("failure_activity_focus", activity?.currentFocus)
        record("failure_dialog_focus", dialog?.currentFocus)
        report.putBoolean("failure_dialog_showing", dialog?.isShowing == true)
    }

    private fun recordMiniFocus() {
        val activity = main ?: return
        val root = activity.findViewById<ViewGroup>(android.R.id.content)
        val mini = root.findViewWithTag<ViewGroup>("return-to-video")
        val field = MainActivity::class.java.getDeclaredField("body").apply { isAccessible = true }
        val body = field.get(activity) as ViewGroup
        val focused = activity.currentFocus
        var ancestor = focused
        while (ancestor != null && ancestor !== body) ancestor = ancestor.parent as? View
        val insideBody = ancestor === body
        report.putBoolean("mini_diagnostic_focused_is_mini", focused === mini)
        report.putBoolean("mini_diagnostic_has_focus", mini?.hasFocus() == true)
        report.putBoolean("mini_diagnostic_shown", mini?.isShown == true)
        report.putBoolean("mini_diagnostic_blocks_descendants", mini?.descendantFocusability == ViewGroup.FOCUS_BLOCK_DESCENDANTS)
        report.putBoolean("mini_diagnostic_focused_inside_body", insideBody)
        report.putBoolean("mini_diagnostic_root_retained", root.getChildAt(0) === originalBrowseRoot)
        report.putBoolean("mini_diagnostic_next_is_focused", insideBody &&
            FocusFinder.getInstance().findNextFocus(body, focused, View.FOCUS_RIGHT) === focused)
        report.putString("mini_diagnostic_screen_bounds", mini?.let { visibleScreenBounds(it)?.flattenToString() } ?: "none")
        val edge = auditHomeEdge
        report.putBoolean("edge_diagnostic_missing", edge == null)
        if (edge != null) {
            report.putBoolean("edge_diagnostic_is_current_focus", focused === edge)
            report.putBoolean("edge_diagnostic_attached", edge.isAttachedToWindow)
            report.putBoolean("edge_diagnostic_has_focus", edge.hasFocus())
            report.putBoolean("edge_diagnostic_shown", edge.isShown)
            report.putInt("edge_diagnostic_width", edge.width)
            report.putInt("edge_diagnostic_height", edge.height)
            report.putInt("edge_diagnostic_scale_x_permille", (edge.scaleX * 1000).toInt())
            report.putInt("edge_diagnostic_scale_y_permille", (edge.scaleY * 1000).toInt())
            val origin = IntArray(2)
            edge.getLocationOnScreen(origin)
            report.putString("edge_diagnostic_screen_origin", "${origin[0]} ${origin[1]}")
            report.putString("edge_diagnostic_visible_screen_bounds", visibleScreenBounds(edge)?.flattenToString() ?: "none")
            var parent = edge.parent as? View
            var index = 0
            while (parent != null && index < 8) {
                if (parent is android.widget.HorizontalScrollView || parent is android.widget.ScrollView) {
                    report.putString("edge_scroll_${index}_class", parent.javaClass.simpleName)
                    report.putString("edge_scroll_${index}_bounds", visibleScreenBounds(parent)?.flattenToString() ?: "none")
                    report.putString("edge_scroll_${index}_offset", "${parent.scrollX} ${parent.scrollY}")
                    report.putString("edge_scroll_${index}_size", "${parent.width} ${parent.height}")
                }
                parent = parent.parent as? View
                index++
            }
        }
    }

    private fun capturePublicScreenshot(name: String) {
        checkAudit(onMain { ownsFocusedPublicPlayback() })
        val screenshot = uiAutomation.takeScreenshot() ?: throw AuditFailure()
        try {
            checkAudit(onMain { ownsFocusedPublicPlayback() })
            FileOutputStream(File(targetContext.cacheDir, name)).use {
                checkAudit(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally { screenshot.recycle() }
    }

    private fun renderedVideoFrames(): Int {
        val counters = auditPlayer?.videoDecoderCounters ?: throw AuditFailure()
        counters.ensureUpdated()
        return counters.renderedOutputBufferCount
    }

    /** Pixel arrays stay in memory and contain only the owned public video ROI, never browsing UI. */
    private fun verifyPublicVideoMotion(name: String, activity: Activity, view: PlayerView, mini: Boolean, framesBefore: Int) {
        stage = "${name}_public_video_motion"
        val (region, first) = samplePublicVideoGrid(activity, view, mini)
        var largestChange = 0
        var renderedDelta = 0
        for (attempt in 1..3) {
            SystemClock.sleep(1_000)
            val (nextRegion, current) = samplePublicVideoGrid(activity, view, mini)
            checkAudit(region == nextRegion)
            val changed = current.indices.count { index ->
                val before = first[index]
                val after = current[index]
                kotlin.math.abs(((before shr 16) and 255) - ((after shr 16) and 255)) +
                    kotlin.math.abs(((before shr 8) and 255) - ((after shr 8) and 255)) +
                    kotlin.math.abs((before and 255) - (after and 255)) >= 24
            }
            largestChange = maxOf(largestChange, changed)
            renderedDelta = onMain { renderedVideoFrames() - framesBefore }
            if (largestChange >= 8 && renderedDelta > 0) break
        }
        report.putInt("${name}_video_changed_grid_cells", largestChange)
        report.putInt("${name}_video_rendered_buffer_delta", renderedDelta)
        report.putBoolean("${name}_visible_public_video_changes", largestChange >= 8)
        report.putBoolean("${name}_decoder_rendering_continues", renderedDelta > 0)
        checkAudit(largestChange >= 8 && renderedDelta > 0)
    }

    private fun publicVideoRegion(activity: Activity, view: PlayerView, mini: Boolean): Rect {
        checkAudit(testUrl == PUBLIC_SAMPLE && auditPlayer != null && InAppPlaybackSession.playerOrNull === auditPlayer)
        checkAudit(InAppPlaybackSession.matches(Intent().putExtra("url", PUBLIC_SAMPLE).putExtra("key", "")))
        checkAudit(!activity.isFinishing && !activity.isDestroyed && activity.hasWindowFocus())
        checkAudit(view.isAttachedToWindow && view.hasWindowFocus() && view.player === auditPlayer &&
            view.rootView === activity.window.decorView && auditPlayer!!.isPlaying)
        if (mini) {
            checkAudit(activity === main && view === firstMiniPlayerView && main!!.currentPlaybackOptionsDialog?.isShowing != true)
        } else {
            checkAudit(activity === fullscreen && view === playerViewFor(fullscreen!!) && fullscreen!!.currentOptionSheet?.isShowing != true)
        }
        val surface = view.videoSurfaceView ?: throw AuditFailure()
        checkAudit(fullyRendered(surface))
        val video = visibleScreenBounds(surface) ?: throw AuditFailure()
        // Inset from surface edges; keep the mini ROI above its caption and fullscreen ROI above chrome.
        return Rect(video.left + video.width() * 20 / 100, video.top + video.height() * 20 / 100,
            video.left + video.width() * 80 / 100, video.top + video.height() * (if (mini) 50 else 60) / 100)
            .also { checkAudit(it.width() >= 40 && it.height() >= 24) }
    }

    private fun samplePublicVideoGrid(activity: Activity, view: PlayerView, mini: Boolean): Pair<Rect, IntArray> {
        val region = onMain { publicVideoRegion(activity, view, mini) }
        // Sample the owned video surface directly. Some TV firmware returns stale compositor
        // screenshots after fullscreen-to-mini handoff even while the decoder renders new frames.
        val pixels = Bitmap.createBitmap(240, 144, Bitmap.Config.ARGB_8888)
        try {
            val copied = java.util.concurrent.CountDownLatch(1)
            var copyResult = android.view.PixelCopy.ERROR_UNKNOWN
            onMain {
                val surface = view.videoSurfaceView as? android.view.SurfaceView ?: throw AuditFailure()
                val location = IntArray(2).also(surface::getLocationOnScreen)
                val local = Rect(region).apply { offset(-location[0], -location[1]) }
                checkAudit(Rect(0, 0, surface.width, surface.height).contains(local))
                android.view.PixelCopy.request(surface, local, pixels, { result ->
                    copyResult = result; copied.countDown()
                }, android.os.Handler(android.os.Looper.getMainLooper()))
            }
            checkAudit(copied.await(5, java.util.concurrent.TimeUnit.SECONDS) && copyResult == android.view.PixelCopy.SUCCESS)
            // Revalidate after capture as well: user navigation cannot make an unrelated window a sample.
            checkAudit(onMain { publicVideoRegion(activity, view, mini) == region })
            val grid = IntArray(20 * 12) { index ->
                val x = ((index % 20 + 0.5) * pixels.width / 20).toInt().coerceIn(1, pixels.width - 2)
                val y = ((index / 20 + 0.5) * pixels.height / 12).toInt().coerceIn(1, pixels.height - 2)
                var red = 0; var green = 0; var blue = 0
                for (dy in -1..1) for (dx in -1..1) {
                    val color = pixels.getPixel(x + dx, y + dy)
                    red += (color shr 16) and 255; green += (color shr 8) and 255; blue += color and 255
                }
                (red / 9 shl 16) or (green / 9 shl 8) or (blue / 9)
            }
            return region to grid
        } finally {
            pixels.recycle()
        }
    }

    private fun ownsFocusedPublicPlayback(): Boolean {
        val activity = fullscreen ?: return false
        if (testUrl != PUBLIC_SAMPLE || auditPlayer == null ||
            InAppPlaybackSession.playerOrNull !== auditPlayer || activity.isFinishing || activity.isDestroyed) return false
        if (!InAppPlaybackSession.matches(Intent().putExtra("url", PUBLIC_SAMPLE).putExtra("key", ""))) return false
        val ownsActivityFocus = activity.hasWindowFocus()
        val sheet = activity.currentOptionSheet
        val ownsSheetFocus = sheet?.isShowing == true && sheet.window?.decorView?.hasWindowFocus() == true
        return ownsActivityFocus || ownsSheetFocus
    }

    private fun descendants(view: View): List<View> = buildList {
        add(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) addAll(descendants(view.getChildAt(index)))
    }

    private fun await(name: String, timeoutMs: Long, predicate: () -> Boolean) {
        stage = name
        val end = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < end) {
            if (onMain(predicate)) return
            SystemClock.sleep(100)
        }
        throw AuditFailure()
    }

    private fun awaitPausedClock(player: ExoPlayer): Long {
        stage = "paused_clock_acknowledgment"
        val deadline = SystemClock.elapsedRealtime() + 5_000
        var lastPosition = Long.MIN_VALUE
        var stableSince = 0L
        while (SystemClock.elapsedRealtime() < deadline) {
            val snapshot = onMain { Triple(player.playWhenReady, player.isPlaying, player.currentPosition) }
            val now = SystemClock.elapsedRealtime()
            if (!snapshot.first && !snapshot.second && lastPosition != Long.MIN_VALUE &&
                kotlin.math.abs(snapshot.third - lastPosition) <= 20) {
                if (stableSince == 0L) stableSince = now
                if (now - stableSince >= 350) return snapshot.third
            } else stableSince = 0L
            lastPosition = snapshot.third
            SystemClock.sleep(100)
        }
        report.putBoolean("pause_clock_play_when_ready", onMain { player.playWhenReady })
        report.putBoolean("pause_clock_is_playing", onMain { player.isPlaying })
        throw AuditFailure()
    }

    private fun checkAudit(value: Boolean) { if (!value) throw AuditFailure() }

    private fun <T> onMain(block: () -> T): T {
        val result = AtomicReference<T>()
        val error = AtomicReference<Throwable?>()
        runOnMainSync {
            try { result.set(block()) } catch (failure: Throwable) { error.set(failure) }
        }
        error.get()?.let { throw it }
        return result.get()
    }

    private class AuditFailure : RuntimeException()

    companion object {
        // Published in shaka-project/shaka-player demo/common/assets.js as multilingual with subtitles.
        private const val PUBLIC_SAMPLE = "https://storage.googleapis.com/shaka-demo-assets/angel-one/dash.mpd"
    }
}

