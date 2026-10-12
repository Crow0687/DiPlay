package com.shilapi.xcertplay

import android.content.Intent
import android.os.Looper
import com.shilapi.xcertplay.airplay.CarPlayMediaButton
import com.shilapi.xcertplay.orchestration.CarPlayController
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBuild

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class LeapmotorAdaptationTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val originalFingerprint = android.os.Build.FINGERPRINT
    private var controller: CarPlayController? = null
    @After fun cleanup() {
        LeapmotorGearMonitor.detach(controller)
        LeapmotorMediaKeys.detach(controller)
        shadowOf(Looper.getMainLooper()).idle()
        ShadowBuild.setFingerprint(originalFingerprint)
        context.getSharedPreferences("wheel-learning", 0).edit().clear().commit()
    }

    @Test fun measuredViewportDoesNotApplyToOtherCarsOrScreenSizes() {
        val c11 = "qti/msmnile_gvmq/msmnile:9/test"
        assertEquals(LeapmotorC11.Viewport(1200, 0, 720, 720), LeapmotorC11.viewport(1920, 720, c11))
        assertNull(LeapmotorC11.viewport(1920, 720, "byd/device"))
        assertNull(LeapmotorC11.viewport(1280, 720, c11))
        val stream = LeapmotorC11.streamConfig()
        assertEquals(720, stream.widthPixels); assertEquals(720, stream.heightPixels)
        assertEquals("maps:/car/instrumentcluster/map", stream.initialUrl)
        assertEquals(255, stream.safeArea!!.top)
    }

    @Test fun c11MeterAndIcuBroadcastFormatsMapWithoutGuessingInternalCommands() {
        val meter = Intent("com.leapmotor.action.METER.CTRL").putExtra("keyEventType", "steeringWheel").putExtra("value", "2")
        assertEquals(CarPlayMediaButton.NEXT, LeapmotorMediaProtocol.button(LeapmotorMediaProtocol.action(meter)))
        meter.putExtra("keyEventType", "unrelated")
        assertNull(LeapmotorMediaProtocol.action(meter))
        val icu = Intent("com.leapmotor.ICU2MMICtrl").putExtra("ICU_MediaSwitch", 1)
        assertEquals(CarPlayMediaButton.PREVIOUS, LeapmotorMediaProtocol.button(LeapmotorMediaProtocol.action(icu)))
        assertNull(LeapmotorMediaProtocol.button("netRadio"))
    }

    @Test fun unnamedMeasuredClusterIsFoundWithoutSelectingTheMainDisplay() {
        ShadowBuild.setFingerprint("qti/msmnile_gvmq/test")
        val manager = context.getSystemService(android.hardware.display.DisplayManager::class.java)
        val id = org.robolectric.shadows.ShadowDisplayManager.addDisplay("w1920dp-h720dp-mdpi", 5)
        shadowOf(manager.getDisplay(id)).apply { setName("Display 2"); setFlags(android.view.Display.FLAG_PRESENTATION) }
        try {
            assertEquals(id, ClusterMapPresentation.findDisplay(context)?.displayId)
        } finally { org.robolectric.shadows.ShadowDisplayManager.removeDisplay(id) }
    }

    @Test fun payloadLengthAndSelfOriginPreventLoopsIncludingFallbackExtras() {
        fun payload(json: String): ByteArray {
            val data = json.toByteArray(Charsets.UTF_8)
            return byteArrayOf((data.size / 100).toByte(), (data.size % 100).toByte()) + data
        }
        assertNull(LeapmotorMediaProtocol.actionFromPayload(byteArrayOf(0)))
        assertEquals("nextOne", LeapmotorMediaProtocol.actionFromPayload(payload("""{"type":"music","data":{"action":"nextOne"}}""")))
        val self = Intent("car.meter.music.BROADCAST")
            .putExtra("receiver", payload("""{"src":"diplay","type":"music","data":{"action":"nextOne"}}"""))
            .putExtra("action", "nextOne")
        assertNull(LeapmotorMediaProtocol.action(self))
        assertNull(LeapmotorWheelLearning.signature(self))
    }

    @Test fun reverseOnlyResumesWhenOurPauseSucceededAndDetachStopsTheReceiver() {
        ShadowBuild.setFingerprint("qti/msmnile_gvmq/test")
        val next = mock(CarPlayController::class.java).also { controller = it }
        `when`(next.sendMediaButton(CarPlayMediaButton.PAUSE)).thenReturn(true)
        LeapmotorGearMonitor.attach(context, next)
        shadowOf(Looper.getMainLooper()).idle()
        fun gear(value: Int) {
            context.sendBroadcast(Intent(LeapmotorGearMonitor.RESPONSE_ACTION).putExtra(LeapmotorGearMonitor.GEAR_EXTRA, value))
            shadowOf(Looper.getMainLooper()).idle()
        }
        assertNull(LeapmotorGearMonitor.videoAllowed())
        gear(1); gear(1); gear(3)
        verify(next, times(1)).sendMediaButton(CarPlayMediaButton.PAUSE)
        verify(next, times(1)).sendMediaButton(CarPlayMediaButton.PLAY)
        assertEquals(false, LeapmotorGearMonitor.videoAllowed())
        gear(2); assertEquals(true, LeapmotorGearMonitor.videoAllowed())
        LeapmotorGearMonitor.detach(next); shadowOf(Looper.getMainLooper()).idle()
        gear(1); assertNull(LeapmotorGearMonitor.videoAllowed())
        verify(next, times(1)).sendMediaButton(CarPlayMediaButton.PAUSE)
    }

    @Test fun learnedBroadcastOverridesDefaultAndBadStorageDoesNotCrash() {
        val key = Intent("com.leapmotor.ICU2MMICtrl").putExtra("mediaSwitch", 2)
        val id = LeapmotorWheelLearning.signature(key)!!
        assertTrue(LeapmotorWheelLearning.save(context, id, "PREVIOUS"))
        assertEquals(CarPlayMediaButton.PREVIOUS, LeapmotorWheelLearning.button(context, id))
        assertFalse(LeapmotorWheelLearning.save(context, "x".repeat(601), "NEXT"))
        context.getSharedPreferences("wheel-learning", 0).edit().putString("bindings-v1", "broken json").commit()
        assertNull(LeapmotorWheelLearning.button(context, id))
    }

    @Test fun anOldOverlayReleaseDoesNotRemoveTheReplacementMirror() {
        val sink = com.shilapi.xcertplay.media.AndroidMediaSink()
        val old = mock(android.view.Surface::class.java)
        val next = mock(android.view.Surface::class.java)
        var releases = 0
        try {
            sink.setMirrorSurface(110, "showmap", old)
            sink.setMirrorSurface(110, "showmap", next)
            sink.releaseMirrorSurface(110, "showmap", old) { releases++ }
            val mirrors = org.robolectric.util.ReflectionHelpers.getField<Map<Pair<Int, String>, android.view.Surface>>(sink, "mirrorSurfaces")
            assertSame(next, mirrors[110 to "showmap"])
            assertEquals(1, releases)
            sink.releaseMirrorSurface(110, "showmap", next) { releases++ }
            assertFalse(mirrors.containsKey(110 to "showmap"))
            assertEquals(2, releases)
        } finally { sink.close() }
    }
}
