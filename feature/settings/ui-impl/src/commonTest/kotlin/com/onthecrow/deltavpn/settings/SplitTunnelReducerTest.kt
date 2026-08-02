package com.onthecrow.deltavpn.settings

import com.onthecrow.deltavpn.vpn.model.InstalledApp
import com.onthecrow.deltavpn.vpn.model.SplitTunnelMode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal class SplitTunnelReducerTest {
    private val reducer = SplitTunnelReducer()

    private val apps = listOf(
        InstalledApp("com.google.android.youtube", "YouTube"),
        InstalledApp("org.telegram.messenger", "Telegram"),
        InstalledApp("com.bank.app", "My Bank"),
    )

    private fun stateWith(
        mode: SplitTunnelMode = SplitTunnelMode.OFF,
        selected: Set<String> = emptySet(),
    ) = SplitTunnelState(
        mode = mode,
        selectedPackages = selected,
        savedMode = mode,
        savedPackages = selected,
        apps = apps,
        appsLoaded = true,
        settingsLoaded = true,
    )

    @Test
    fun togglingAddsThenRemoves() = runTest {
        val added = reducer.reduce(stateWith(), SplitTunnelEvent.OnAppToggled("com.bank.app"))
        assertEquals(setOf("com.bank.app"), added.selectedPackages)

        val removed = reducer.reduce(added, SplitTunnelEvent.OnAppToggled("com.bank.app"))
        assertTrue(removed.selectedPackages.isEmpty())
    }

    @Test
    fun hasChangesTracksTheDraftAgainstWhatIsSaved() = runTest {
        val clean = stateWith()
        assertFalse(clean.hasChanges)

        val dirty = reducer.reduce(clean, SplitTunnelEvent.OnAppToggled("com.bank.app"))
        assertTrue(dirty.hasChanges)

        // Undoing the edit must retract the Apply button, not leave it stuck on screen.
        val reverted = reducer.reduce(dirty, SplitTunnelEvent.OnAppToggled("com.bank.app"))
        assertFalse(reverted.hasChanges)
    }

    @Test
    fun modeChangeCountsAsAChange() = runTest {
        val changed = reducer.reduce(
            stateWith(),
            SplitTunnelEvent.OnModeChanged(SplitTunnelMode.BYPASS_SELECTED),
        )
        assertTrue(changed.hasChanges)
        assertEquals(SplitTunnelMode.BYPASS_SELECTED, changed.mode)
    }

    @Test
    fun loadedSeedsTheDraftWhenNothingIsPending() = runTest {
        val loaded = reducer.reduce(
            stateWith(),
            SplitTunnelEvent.OnSettingsLoaded(SplitTunnelMode.ONLY_SELECTED, setOf("org.telegram.messenger")),
        )
        assertEquals(SplitTunnelMode.ONLY_SELECTED, loaded.mode)
        assertEquals(setOf("org.telegram.messenger"), loaded.selectedPackages)
        assertFalse(loaded.hasChanges)
    }

    @Test
    fun loadedNeverClobbersAnUnappliedDraft() = runTest {
        // The push toggle on the settings screen writes the same record, so a reload can arrive while
        // the user is midway through picking apps. Their selection must survive it.
        val dirty = reducer.reduce(stateWith(), SplitTunnelEvent.OnAppToggled("com.bank.app"))
        val afterReload = reducer.reduce(
            dirty,
            SplitTunnelEvent.OnSettingsLoaded(SplitTunnelMode.BYPASS_SELECTED, setOf("org.telegram.messenger")),
        )

        assertEquals(setOf("com.bank.app"), afterReload.selectedPackages)
        assertTrue(afterReload.hasChanges)
        // …but it does adopt the new baseline, so Apply compares against what is really persisted.
        assertEquals(setOf("org.telegram.messenger"), afterReload.savedPackages)
        assertEquals(SplitTunnelMode.BYPASS_SELECTED, afterReload.savedMode)
    }

    @Test
    fun blankQueryShowsEverything() = runTest {
        assertEquals(apps, stateWith().copy(query = "").visibleApps)
        assertEquals(apps, stateWith().copy(query = "   ").visibleApps)
    }

    @Test
    fun searchTrimsAndIgnoresCase() = runTest {
        val found = stateWith().copy(query = "  teleGRAM  ").visibleApps
        assertEquals(listOf(apps[1]), found)
    }

    @Test
    fun searchAlsoMatchesThePackageName() = runTest {
        val found = stateWith().copy(query = "com.google").visibleApps
        assertEquals(listOf(apps[0]), found)
    }

    @Test
    fun clearingTheQueryRestoresTheFullList() = runTest {
        val filtered = reducer.reduce(stateWith(), SplitTunnelEvent.OnQueryChanged("bank"))
        assertEquals(1, filtered.visibleApps.size)

        val cleared = reducer.reduce(filtered, SplitTunnelEvent.OnQueryCleared)
        assertEquals("", cleared.query)
        assertEquals(apps, cleared.visibleApps)
    }

    @Test
    fun anEmptyAllowlistIsFlagged() = runTest {
        assertTrue(stateWith(mode = SplitTunnelMode.ONLY_SELECTED).warnEmptyAllowlist)
        assertFalse(stateWith(mode = SplitTunnelMode.ONLY_SELECTED, selected = setOf("com.bank.app")).warnEmptyAllowlist)
        // Excluding nothing is a perfectly normal state — it just means everything tunnels.
        assertFalse(stateWith(mode = SplitTunnelMode.BYPASS_SELECTED).warnEmptyAllowlist)
    }

    @Test
    fun theFirstLoadSeedsTheDraftEvenIfTheUserTouchedTheModeSelectorWhileLoading() {
        // The mode selector renders above the spinner, and a restored back stack can land straight on
        // this screen while DataStore is still cold. Measuring "has the user changed anything?" against
        // the constructor defaults would let that tap block the real values from ever arriving — and
        // Apply would then write an empty selection over whatever was saved.
        runTest {
            val cold = SplitTunnelState(apps = apps, appsLoaded = true)
            val touchedEarly = reducer.reduce(cold, SplitTunnelEvent.OnModeChanged(SplitTunnelMode.ONLY_SELECTED))
            assertFalse(touchedEarly.hasChanges, "nothing is 'changed' before we know what is saved")

            val loaded = reducer.reduce(
                touchedEarly,
                SplitTunnelEvent.OnSettingsLoaded(SplitTunnelMode.BYPASS_SELECTED, setOf("com.bank.app")),
            )
            assertEquals(setOf("com.bank.app"), loaded.selectedPackages)
            assertEquals(SplitTunnelMode.BYPASS_SELECTED, loaded.mode)
            assertFalse(loaded.hasChanges)
        }
    }
}
