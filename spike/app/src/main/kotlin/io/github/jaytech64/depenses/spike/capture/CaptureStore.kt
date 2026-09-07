package io.github.jaytech64.depenses.spike.capture

import android.content.Context
import android.content.pm.PackageManager
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File

/**
 * Append-only capture log.
 *
 * The spike's deliverable is a JSON corpus, so the storage format is the export
 * format: one JSON object per line. No database, no schema, no migrations —
 * all of which would be thrown away when the real app is built.
 */
class CaptureStore private constructor(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeLock = Mutex()

    private val capturesFile = File(context.filesDir, CAPTURES_FILE)
    private val sightingsFile = File(context.filesDir, SIGHTINGS_FILE)

    private val _captures = MutableStateFlow<List<Capture>>(emptyList())
    val captures: StateFlow<List<Capture>> = _captures.asStateFlow()

    private val _sightings = MutableStateFlow<List<Sighting>>(emptyList())
    val sightings: StateFlow<List<Sighting>> = _sightings.asStateFlow()

    private val watchList = WatchList(context)

    init {
        scope.launch { reload() }
    }

    /**
     * Called on the listener's callback thread. Extraction happens here because
     * the notification must be read before the system recycles it; only the file
     * write is deferred.
     */
    fun onNotificationPosted(notification: StatusBarNotification) {
        val packageName = notification.packageName
        if (packageName == context.packageName) return

        val label = appLabel(packageName)
        val watched = watchList.isWatched(packageName)
        val json = if (watched) notification.toCaptureJson(label) else null
        val postedAt = notification.postTime

        scope.launch {
            writeLock.withLock {
                if (json != null) capturesFile.appendText(json.toString() + "\n")
                recordSighting(packageName, label, postedAt, captured = watched)
            }
            reload()
        }
    }

    fun setWatched(packageName: String, watched: Boolean) {
        watchList.setWatched(packageName, watched)
        scope.launch { reload() }
    }

    fun isWatched(packageName: String): Boolean = watchList.isWatched(packageName)

    /** The full corpus, ready to hand to a parser test suite. */
    fun exportJson(): String {
        val captures = capturesFile.readLinesOrEmpty().mapNotNull { line ->
            runCatching { JSONObject(line) }.getOrNull()
        }
        return JSONObject().apply {
            put("exportedAtUtcMillis", System.currentTimeMillis())
            put("captureCount", captures.size)
            put("watchedPackages", watchList.watched().sorted().joinToString(","))
            put("captures", org.json.JSONArray(captures))
        }.toString(2)
    }

    fun clear() {
        scope.launch {
            writeLock.withLock {
                capturesFile.delete()
                sightingsFile.delete()
            }
            reload()
        }
    }

    private fun recordSighting(packageName: String, label: String, postedAt: Long, captured: Boolean) {
        val existing = readSightings().associateBy { it.packageName }.toMutableMap()
        val previous = existing[packageName]
        existing[packageName] = Sighting(
            packageName = packageName,
            appLabel = label,
            firstSeenUtcMillis = previous?.firstSeenUtcMillis ?: postedAt,
            lastSeenUtcMillis = postedAt,
            count = (previous?.count ?: 0) + 1,
            capturedCount = (previous?.capturedCount ?: 0) + if (captured) 1 else 0,
        )
        val array = org.json.JSONArray()
        existing.values.forEach { array.put(it.toJson()) }
        sightingsFile.writeText(array.toString())
    }

    private fun readSightings(): List<Sighting> {
        if (!sightingsFile.exists()) return emptyList()
        val array = runCatching { org.json.JSONArray(sightingsFile.readText()) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            runCatching { Sighting.fromJson(array.getJSONObject(index)) }.getOrNull()
        }
    }

    private fun reload() {
        _captures.value = capturesFile.readLinesOrEmpty()
            .mapNotNull { line -> runCatching { Capture.fromJson(JSONObject(line)) }.getOrNull() }
            .asReversed()
        _sightings.value = readSightings()
            .map { it.copy(watched = watchList.isWatched(it.packageName)) }
            .sortedByDescending { it.lastSeenUtcMillis }
    }

    private fun appLabel(packageName: String): String = try {
        val info = context.packageManager.getApplicationInfo(packageName, 0)
        context.packageManager.getApplicationLabel(info).toString()
    } catch (error: PackageManager.NameNotFoundException) {
        packageName
    }

    private fun File.readLinesOrEmpty(): List<String> =
        if (exists()) readLines().filter { it.isNotBlank() } else emptyList()

    companion object {
        private const val CAPTURES_FILE = "captures.jsonl"
        private const val SIGHTINGS_FILE = "sightings.json"

        @Volatile
        private var instance: CaptureStore? = null

        fun get(context: Context): CaptureStore =
            instance ?: synchronized(this) {
                instance ?: CaptureStore(context.applicationContext).also { instance = it }
            }
    }
}
