package relay.clip.research

import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Device-side dump for S2. Pulled with `adb shell run-as relay.demo.clip cat files/research-last.txt`. */
internal class ResearchTrace(private val file: File) {
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val lock = Any()

    fun reset(topic: String) {
        file.parentFile?.mkdirs()
        file.writeText("topic=${topic.take(500)}\nstart=${System.currentTimeMillis()}\n\n")
        line("RESET")
    }

    fun line(msg: String) {
        val row = "${fmt.format(Date())} $msg"
        synchronized(lock) { file.appendText(row + "\n") }
        Log.i(TAG, row.take(1000))
    }

    fun fail(t: Throwable) {
        line("FAIL ${t.javaClass.name}: ${t.message}")
        synchronized(lock) { file.appendText(t.stackTraceToString() + "\n") }
        Log.e(TAG, "FAIL", t)
    }

    fun done() {
        line("DONE")
    }

    companion object {
        const val TAG = "ClipResearch"
        const val FILE_NAME = "research-last.txt"
    }
}
