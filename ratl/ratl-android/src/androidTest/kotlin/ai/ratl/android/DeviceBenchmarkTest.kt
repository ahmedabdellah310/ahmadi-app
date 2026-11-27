package ai.ratl.android

import ai.ratl.core.ForcedAligner
import ai.ratl.core.Frames
import ai.ratl.core.Windowing
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * G5 and G6 on the benchmark device. SPEC 7.2, 7.3.
 *
 * SPEC 7.3 forbids reporting these from a desktop CPU or an emulator, so this
 * runs as an instrumentation test on real hardware and records the device
 * identity alongside the numbers.
 *
 * G5 - real-time factor, single-threaded, feature extraction included, through
 *      the frozen 15 s / 10 s windowing. Ceiling 0.5.
 * G6 - peak resident set size, read from /proc/self/status VmHWM, which is the
 *      kernel's own high-water mark rather than a sampled approximation.
 *      Ceiling 400 MB.
 *
 * Inputs are pushed to the device rather than bundled, so the same bytes the
 * desktop harness used are measured here:
 *
 *   adb push models/out/ratl-align-v1-e2e-fp32.onnx  /sdcard/ratl/model/
 *   adb push models/out/vocab.json                   /sdcard/ratl/model/
 *   adb push eval/bench_pcm/<reciter>/<clip>.s16     /sdcard/ratl/pcm/
 */
@RunWith(AndroidJUnit4::class)
class DeviceBenchmarkTest {

    // Scoped storage (Android 11+) means an app cannot read /sdcard/ratl even
    // when adb pushed files there successfully. The app-private external dir is
    // readable without any permission, so that is the primary location; the
    // others are kept as fallbacks for older images and for shell-driven runs.
    private val diagnostics = StringBuilder()

    /**
     * Fixtures live in the test APK and are extracted to internal storage.
     *
     * Pushing them with adb does not work on Android 11+: `adb shell` and the
     * app see different FUSE views of `Android/data`, so files that adb writes
     * and can list are invisible to the app process. `/sdcard/ratl` behaves
     * worse still - it can be stat'd but not listed. Internal storage is the
     * only location both sides agree on, and bundling the model is also how a
     * real integrator would ship one.
     */
    private val root: File by lazy {
        val inst = InstrumentationRegistry.getInstrumentation()
        val dest = File(inst.targetContext.filesDir, "ratl")
        val assets = inst.context.assets
        for (sub in listOf("model", "pcm")) {
            File(dest, sub).mkdirs()
            for (name in assets.list("ratl/$sub").orEmpty()) {
                val out = File(dest, "$sub/$name")
                if (out.exists() && out.length() > 0) continue
                assets.open("ratl/$sub/$name").use { input ->
                    out.outputStream().use { o -> input.copyTo(o, 1 shl 16) }
                }
            }
        }
        diagnostics.append("  extracted from APK assets to ").append(dest).append('\n')
        diagnostics.append("  model=").append(File(dest, "model").list()?.joinToString(","))
            .append("  pcm=").append(File(dest, "pcm").list()?.size).append('\n')
        dest
    }
    private val modelDir: File get() = File(root, "model")
    private val pcmDir: File get() = File(root, "pcm")

    private fun deviceInfo(): Map<String, String> = linkedMapOf(
        "manufacturer" to android.os.Build.MANUFACTURER,
        "brand" to android.os.Build.BRAND,
        "model" to android.os.Build.MODEL,
        "device" to android.os.Build.DEVICE,
        "board" to android.os.Build.BOARD,
        "hardware" to android.os.Build.HARDWARE,
        "soc_manufacturer" to (android.os.Build.SOC_MANUFACTURER ?: "unknown"),
        "soc_model" to (android.os.Build.SOC_MODEL ?: "unknown"),
        "android_release" to android.os.Build.VERSION.RELEASE,
        "sdk_int" to android.os.Build.VERSION.SDK_INT.toString(),
        "build_id" to android.os.Build.ID,
        "fingerprint" to android.os.Build.FINGERPRINT,
        "supported_abis" to android.os.Build.SUPPORTED_ABIS.joinToString(","),
    )

    /** Peak RSS in MB, from the kernel's own high-water mark. */
    private fun peakRssMb(): Double {
        val line = File("/proc/self/status").readLines()
            .firstOrNull { it.startsWith("VmHWM:") } ?: return -1.0
        val kb = line.filter { it.isDigit() }.toLongOrNull() ?: return -1.0
        return kb / 1024.0
    }

    private fun currentRssMb(): Double {
        val line = File("/proc/self/status").readLines()
            .firstOrNull { it.startsWith("VmRSS:") } ?: return -1.0
        val kb = line.filter { it.isDigit() }.toLongOrNull() ?: return -1.0
        return kb / 1024.0
    }

    private fun totalRamMb(): Long {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val am = ctx.getSystemService(android.content.Context.ACTIVITY_SERVICE)
            as android.app.ActivityManager
        val mi = android.app.ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        return mi.totalMem / (1024 * 1024)
    }

    private fun thermalStatus(): String {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val pm = ctx.getSystemService(android.content.Context.POWER_SERVICE)
            as android.os.PowerManager
        return when (pm.currentThermalStatus) {
            android.os.PowerManager.THERMAL_STATUS_NONE -> "NONE"
            android.os.PowerManager.THERMAL_STATUS_LIGHT -> "LIGHT"
            android.os.PowerManager.THERMAL_STATUS_MODERATE -> "MODERATE"
            android.os.PowerManager.THERMAL_STATUS_SEVERE -> "SEVERE"
            android.os.PowerManager.THERMAL_STATUS_CRITICAL -> "CRITICAL"
            android.os.PowerManager.THERMAL_STATUS_EMERGENCY -> "EMERGENCY"
            android.os.PowerManager.THERMAL_STATUS_SHUTDOWN -> "SHUTDOWN"
            else -> "UNKNOWN"
        }
    }

    @Test
    fun measureG5AndG6() {
        val log = StringBuilder()
        fun say(s: String) { log.append(s).append('\n'); android.util.Log.i("RATL-BENCH", s) }

        say("=== DEVICE ===")
        say("  fixture_root = $root")
        say(diagnostics.toString().trimEnd())
        deviceInfo().forEach { (k, v) -> say("  $k = $v") }
        say("  total_ram_mb = ${totalRamMb()}")
        say("  thermal_before = ${thermalStatus()}")

        assertTrue(
            "model dir $modelDir missing - push the model with adb first",
            modelDir.isDirectory,
        )
        val clips = pcmDir.listFiles { f -> f.name.endsWith(".s16") }?.sortedBy { it.name }
        assertTrue("no .s16 clips in $pcmDir - push them with adb first", !clips.isNullOrEmpty())

        val rssBaseline = currentRssMb()
        say("  rss_baseline_mb = %.1f".format(rssBaseline))

        val engine = RatlAndroid.engineFromDir(modelDir, threads = 1)
        val modelId = engine.modelInfo().modelId
        say("  model_id = $modelId")

        // ---- G5: RTF through the frozen windowing, single thread
        val model = ai.ratl.acoustic.OnnxAcousticModel(
            modelDir.listFiles { f -> f.name.endsWith(".onnx") }!!.minByOrNull { it.name }!!,
            engine.modelInfo().vocabularySize, 1,
        )
        val perClip = ArrayList<Triple<String, Double, Double>>()

        // warm-up: first inference pays class loading and arena allocation and
        // is not representative of steady-state cost
        run {
            val warm = RatlAndroid.pcm16ToFloat(clips!!.first().readBytes())
                .copyOf(Frames.SAMPLE_RATE)
            Windowing.runWindows(warm, model)
        }

        for (f in clips!!) {
            val audio = RatlAndroid.pcm16ToFloat(f.readBytes())
            val durS = audio.size.toDouble() / Frames.SAMPLE_RATE
            var best = Double.MAX_VALUE
            repeat(3) {
                val t0 = System.nanoTime()
                val logits = Windowing.runWindows(audio, model)
                ForcedAligner.logSoftmax(logits)
                val elapsed = (System.nanoTime() - t0) / 1e9
                best = minOf(best, elapsed / durS)
            }
            perClip.add(Triple(f.name, durS, best))
            say("  clip ${f.name}  dur=%.2fs  rtf=%.5f".format(durS, best))
        }
        model.close()

        val rtf = perClip.sumOf { it.third } / perClip.size
        val worst = perClip.maxOf { it.third }
        val peak = peakRssMb()

        say("")
        say("=== RESULTS (SPEC 7.3 device) ===")
        say("  G5 rtf_mean        = %.5f   ceiling 0.5".format(rtf))
        say("  G5 rtf_worst_clip  = %.5f".format(worst))
        say("  G5 PASS            = ${rtf <= 0.5}")
        say("  G6 peak_rss_mb     = %.1f   ceiling 400".format(peak))
        say("  G6 PASS            = ${peak in 0.0..400.0}")
        say("  thermal_after      = ${thermalStatus()}")
        say("  clips              = ${perClip.size}")
        say("  threads            = 1 (G5 is specified single-threaded)")

        File(root, "g5_g6_result.txt").writeText(log.toString())

        assertTrue("G5 RTF %.5f exceeds the 0.5 ceiling".format(rtf), rtf <= 0.5)
        assertTrue("G6 peak RSS %.1f MB exceeds the 400 MB ceiling".format(peak), peak <= 400.0)
        engine.close()
    }
}
