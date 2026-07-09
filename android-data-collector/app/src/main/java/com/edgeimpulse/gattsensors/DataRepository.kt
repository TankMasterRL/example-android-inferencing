package com.edgeimpulse.gattsensors

import android.content.Context
import android.provider.Settings
import android.util.Log
import com.edgeimpulse.gattsensors.senml.Senml
import com.edgeimpulse.gattsensors.senml.SenmlIngestion
import com.edgeimpulse.gattsensors.senml.SenmlRecord
import com.edgeimpulse.gattsensors.senml.SenmlUnits
import com.google.android.gms.wearable.MessageEvent
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*

data class IngestionRequest(val protected: Protected, val payload: IngestionPayload)
data class Protected(val ver: String, val alg: String, val signature: String)
data class IngestionPayload(val device_name: String, val device_type: String, val interval_ms: Number, val sensors: List<SensorInfo>, val values: List<List<Float>>)

/**
 * On-device offline logging format: classic CSV rows, or line-delimited SenML
 * (RFC 8428) where each line is one self-describing JSON pack carrying channel
 * names, units and timestamps.
 */
enum class OfflineLogFormat { CSV, SENML }

/** Metadata for one on-device CSV dataset shown in the Datasets tab. */
data class StoredDataset(
    val file: File,
    val name: String,
    val sizeBytes: Long,
    val sampleCount: Int,
    val createdAt: Long,
    val headers: List<String>,
)

/** First N rows of a CSV (header excluded) shown in the dataset previewer. */
data class DatasetPreview(val headers: List<String>, val rows: List<String>)

/**
 * Full in-memory view of a CSV dataset for the spreadsheet editor.
 * [rows] is a list of pre-split records (no header). Each row may legally
 * have a different length to [headers] — short rows are right-padded by the
 * editor UI; over-long rows have their trailing columns merged into the
 * last cell so nothing is silently lost on round-trip.
 */
data class EditableDataset(
    val file: File,
    val headers: List<String>,
    val rows: List<List<String>>,
)

class DataRepository(private val context: Context, private val apiKeyStore: ApiKeyStore) {

    private val client = OkHttpClient()
    private val gson = Gson()
    private val deviceId: String by lazy {
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            ?: "android-ei-device"
    }

    // For offline logging
    private var isLoggingOffline = false
    private var offlineFormat = OfflineLogFormat.CSV
    private var logFileWriter: FileWriter? = null
    private var offlineHeaders: List<String> = emptyList()
    // When true, the header row is written from the first sample's actual
    // value keys rather than the caller-provided default. This avoids
    // empty columns when the UI hasn't told us which sensor is being
    // recorded (e.g. gyro samples keyed `gyro_0` arriving while headers
    // default to `accelX`/`accelY`/`accelZ`).
    private var offlineHeadersDeferred = false

    // For remote management in-memory sampling
    private val remoteSampleData = mutableListOf<SensorData>()
    private var isSamplingForRemote = false

    fun startOfflineLogging(
        headers: List<String> = emptyList(),
        format: OfflineLogFormat = OfflineLogFormat.CSV,
    ) {
        if (isLoggingOffline) return
        isLoggingOffline = true
        offlineFormat = format
        // SenML lines are self-describing, so the CSV header machinery only
        // applies to the CSV format.
        offlineHeadersDeferred = format == OfflineLogFormat.CSV && headers.isEmpty()
        offlineHeaders = if (format == OfflineLogFormat.CSV && !offlineHeadersDeferred)
                             listOf("timestamp") + headers
                         else listOf("timestamp")
        val dir = File(context.getExternalFilesDir(null), "sensor_logs")
        if (!dir.exists()) dir.mkdirs()
        val ext = if (format == OfflineLogFormat.SENML) "senml" else "csv"
        val file = File(dir, "sensor_data_${getDateString()}.$ext")
        try {
            logFileWriter = FileWriter(file)
            if (format == OfflineLogFormat.CSV && !offlineHeadersDeferred) {
                logFileWriter?.append(offlineHeaders.joinToString(",") + "\n")
            }
        } catch (e: IOException) {
            Log.e("DataRepository", "Error creating log file", e)
            logFileWriter = null
            isLoggingOffline = false
        }
    }

    fun stopOfflineLogging() {
        if (!isLoggingOffline) return
        isLoggingOffline = false
        try {
            logFileWriter?.flush()
            logFileWriter?.close()
        } catch (e: IOException) {
            Log.e("DataRepository", "Error closing log file", e)
        }
        logFileWriter = null
    }

    // Flush the log every N writes so an app crash doesn't lose buffered data.
    private var logWriteCount = 0
    private val logFlushInterval = 20

    /** Append one line to the offline log, honouring the periodic flush. */
    private fun appendOfflineLine(line: String) {
        try {
            logFileWriter?.append(line)?.append('\n')
            if (++logWriteCount % logFlushInterval == 0) {
                logFileWriter?.flush()
            }
        } catch (e: IOException) {
            Log.e("DataRepository", "Error writing to log file", e)
        }
    }

    /**
     * Append one sample as a single-line SenML pack: `bn` = this device,
     * `bt` = phone-clock epoch seconds, one record per channel with units
     * from [SenmlUnits] (device-declared [units] win when provided).
     */
    private fun appendOfflineSenmlLine(
        timestampMs: Long,
        names: List<String>,
        values: FloatArray,
        units: List<String?>? = null,
    ) {
        val records = mutableListOf<SenmlRecord>()
        var basesPending = true
        for (i in values.indices) {
            val v = values[i]
            if (!v.isFinite()) continue
            val name = names.getOrNull(i) ?: "col_$i"
            records.add(SenmlRecord(
                bn = if (basesPending) "$deviceId:" else null,
                bt = if (basesPending) timestampMs / 1000.0 else null,
                n  = name,
                u  = units?.getOrNull(i) ?: SenmlUnits.unitFor(name),
                v  = v.toString().toDouble(),
            ))
            basesPending = false
        }
        if (records.isNotEmpty()) appendOfflineLine(Senml.toJson(records))
    }

    fun saveSensorData(data: SensorData) {
        if (isLoggingOffline) {
            if (offlineFormat == OfflineLogFormat.SENML) {
                val pack = Senml.packFromSensorData(deviceId, listOf(data))
                if (pack.isNotEmpty()) appendOfflineLine(Senml.toJson(pack))
            } else try {
                // Lazily fix the header row to whatever the first real sample
                // carries — guarantees the value columns line up with the
                // keys we look up below.
                if (offlineHeadersDeferred && data.values.isNotEmpty()) {
                    offlineHeaders = listOf("timestamp") + data.values.keys.toList()
                    logFileWriter?.append(offlineHeaders.joinToString(",") + "\n")
                    offlineHeadersDeferred = false
                }
                val values = offlineHeaders.map { header -> if (header == "timestamp") data.timestamp.toString() else data.values[header]?.toString() ?: "" }
                logFileWriter?.append(values.joinToString(",") + "\n")
                if (++logWriteCount % logFlushInterval == 0) {
                    logFileWriter?.flush()
                }
            } catch (e: IOException) {
                Log.e("DataRepository", "Error writing to CSV file", e)
            }
        }
        if (isSamplingForRemote) {
            remoteSampleData.add(data)
        }
    }

    fun onMessageReceived(messageEvent: MessageEvent) {
        when (messageEvent.path) {
            WearProtocol.PATH_LEGACY_ACCEL -> {
                // Legacy "x,y,z" CSV — kept for old wear builds.
                val data = String(messageEvent.data)
                val values = data.split(",").mapNotNull { it.trim().toFloatOrNull() }
                if (values.size >= 3) {
                    saveSensorData(SensorData(
                        System.currentTimeMillis(),
                        mapOf("accelX" to values[0], "accelY" to values[1], "accelZ" to values[2])
                    ))
                    appendWearSamples("accel", System.currentTimeMillis(),
                        floatArrayOf(values[0], values[1], values[2]))
                }
            }
            WearProtocol.PATH_SAMPLES -> {
                // `<key>|<ts>|v0,v1,v2` per line — multiple lines per message.
                String(messageEvent.data).lineSequence().forEach { line ->
                    if (line.isBlank()) return@forEach
                    val parts = line.split('|', limit = 3)
                    if (parts.size != 3) return@forEach
                    val key = parts[0]
                    val ts  = parts[1].toLongOrNull() ?: System.currentTimeMillis()
                    val vs  = parts[2].split(',').mapNotNull { it.trim().toFloatOrNull() }
                    if (vs.isNotEmpty()) appendWearSamples(key, ts, vs.toFloatArray())
                }
            }
        }
    }

    suspend fun startRemoteSamplingAndCollect(timeoutMs: Int) {
        if (isSamplingForRemote) return
        isSamplingForRemote = true
        remoteSampleData.clear()
        delay(timeoutMs.toLong())
        isSamplingForRemote = false
    }

    fun uploadCollectedRemoteSample(
        label: String,
        hmacKey: String,
        path: String,
        sensorName: String,
        intervalMs: Int,
        lengthMs: Int
    ) {
        // Derive frequency and window length from the sample-request parameters.
        val frequencyHz = if (intervalMs > 0) 1000.0 / intervalMs else 62.5
        val maxSampleLengthS = (lengthMs / 1000).coerceAtLeast(1)
        val sensorInfo = SensorInfo(sensorName, listOf(frequencyHz), maxSampleLengthS)
        val values = remoteSampleData.map { it.values.values.toList() }
        val payload = IngestionPayload(deviceId, "ANDROID_PHONE", intervalMs.coerceAtLeast(1), listOf(sensorInfo), values)
        val requestBody = IngestionRequest(Protected("v1", "none", "00"), payload)

        val request = Request.Builder()
            .url("https://ingestion.edgeimpulse.com$path")
            .header("x-api-key", apiKeyStore.get())
            .header("x-label", label)
            .header("x-hmac-key", hmacKey)
            .post(gson.toJson(requestBody).toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute()
    }

    fun uploadStoredLogFiles(label: String) {
        CoroutineScope(Dispatchers.IO).launch {
            val dir = File(context.getExternalFilesDir(null), "sensor_logs")
            if (!dir.exists()) return@launch

            dir.listFiles { f -> f.extension == "csv" }?.forEach { file ->
                uploadFile(file, label)
                file.delete()
            }
            // SenML logs are converted to the EI data-acquisition format at
            // flush time (ingestion doesn't accept SenML) and deleted only
            // once the upload actually succeeded.
            dir.listFiles { f -> f.extension == "senml" }?.forEach { file ->
                postSenmlAsIngestion(file, label)
                    .onSuccess {
                        Log.d("DataRepository", "Successfully uploaded ${file.name}")
                        file.delete()
                    }
                    .onFailure {
                        Log.e("DataRepository", "SenML upload failed for ${file.name}: ${it.message}")
                    }
            }
        }
    }

    /**
     * Convert a line-delimited SenML log to the Edge Impulse data-acquisition
     * format and POST it. A file with zero valid samples fails without upload
     * so callers keep it on disk (likely corrupt — leave it visible in the
     * Datasets tab rather than silently destroy data).
     */
    private fun postSenmlAsIngestion(file: File, label: String): Result<Unit> {
        return try {
            val conversion = file.useLines { SenmlIngestion.convert(it, deviceId) }
                ?: return Result.failure(IOException("no valid SenML samples in ${file.name}"))
            if (conversion.skippedLines > 0) {
                Log.w("DataRepository",
                    "${file.name}: skipped ${conversion.skippedLines} unparseable SenML line(s)")
            }
            val requestBody = IngestionRequest(Protected("v1", "none", "00"), conversion.payload)
            val request = Request.Builder()
                .url("https://ingestion.edgeimpulse.com/api/training/data")
                .header("x-api-key", apiKeyStore.get())
                .header("x-label", label)
                .post(gson.toJson(requestBody).toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Result.failure(IOException("HTTP ${response.code}: ${response.body?.string()}"))
                } else {
                    Result.success(Unit)
                }
            }
        } catch (e: IOException) {
            Result.failure(e)
        }
    }

    // -------------------------------------------------------------------------
    // On-device dataset management (browse / preview / rename / delete /
    // share / upload individual files before deciding what to keep).
    // -------------------------------------------------------------------------

    /** Folder where offline-logged CSVs live. Created lazily. */
    fun datasetsDir(): File {
        val dir = File(context.getExternalFilesDir(null), "sensor_logs")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * List every CSV in the dataset folder along with cheap-to-compute
     * metadata (size, sample count = non-header lines, creation time, and the
     * comma-separated headers from line 1). Sample count requires reading the
     * file once so this is O(total bytes) — fine for the modest CSVs the app
     * produces, but call from a background dispatcher.
     */
    fun listStoredDatasets(): List<StoredDataset> {
        val files = datasetsDir().listFiles { f ->
            f.extension == "csv" || f.extension == "senml"
        } ?: return emptyList()
        return files.sortedByDescending { it.lastModified() }.map { f ->
            var headers: List<String> = emptyList()
            var sampleCount = 0
            try {
                if (f.extension == "senml") {
                    // Every line is one sample; channel names come from the
                    // first parseable pack (best-effort).
                    f.bufferedReader().useLines { lines ->
                        for (line in lines) {
                            if (line.isBlank()) continue
                            sampleCount++
                            if (headers.isEmpty()) headers = senmlChannelNames(line)
                        }
                    }
                } else {
                    f.bufferedReader().use { br ->
                        val first = br.readLine()
                        if (first != null) {
                            headers = first.split(',').map { it.trim() }
                            while (br.readLine() != null) sampleCount++
                        }
                    }
                }
            } catch (e: IOException) {
                Log.w("DataRepository", "Failed to read ${f.name}", e)
            }
            StoredDataset(
                file        = f,
                name        = f.name,
                sizeBytes   = f.length(),
                sampleCount = sampleCount,
                createdAt   = f.lastModified(),
                headers     = headers
            )
        }
    }

    /** Channel names from one line of a line-delimited SenML log (best-effort). */
    private fun senmlChannelNames(line: String): List<String> = try {
        Senml.resolve(Senml.fromJson(line)).map { it.shortName }
    } catch (e: RuntimeException) {
        emptyList()
    }

    /**
     * First [maxRows] data rows of a dataset, as raw lines. CSV: header row
     * excluded, headers from line 1. SenML: every line is a data row (raw
     * JSON, rendered as-is by the preview dialog); headers are the channel
     * names of the first parseable pack.
     */
    fun previewDataset(file: File, maxRows: Int = 50): DatasetPreview {
        val rows = mutableListOf<String>()
        var headers = emptyList<String>()
        try {
            if (file.extension == "senml") {
                file.bufferedReader().useLines { lines ->
                    for (line in lines) {
                        if (line.isBlank()) continue
                        if (headers.isEmpty()) headers = senmlChannelNames(line)
                        if (rows.size >= maxRows) break
                        rows.add(line)
                    }
                }
                return DatasetPreview(headers, rows)
            }
            file.bufferedReader().use { br ->
                val first = br.readLine() ?: return DatasetPreview(emptyList(), emptyList())
                headers = first.split(',').map { it.trim() }
                var line = br.readLine()
                while (line != null && rows.size < maxRows) {
                    rows.add(line)
                    line = br.readLine()
                }
            }
        } catch (e: IOException) {
            Log.w("DataRepository", "Preview failed for ${file.name}", e)
        }
        return DatasetPreview(headers, rows)
    }

    /**
     * Rename a dataset on disk. [newName] may omit the extension; the file's
     * existing one (.csv / .senml) is preserved. Returns the renamed file, or
     * null on failure (e.g. target already exists, or the file is currently
     * being written to).
     */
    fun renameDataset(file: File, newName: String): File? {
        if (isLoggingOffline) return null  // refuse while the writer is open
        val safe = newName.trim()
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .takeIf { it.isNotEmpty() } ?: return null
        val ext = file.extension
        val target = File(file.parentFile, if (safe.endsWith(".$ext")) safe else "$safe.$ext")
        if (target.exists()) return null
        return if (file.renameTo(target)) target else null
    }

    /** Delete a dataset. Returns true on success. */
    fun deleteDataset(file: File): Boolean = file.delete()

    // -- Spreadsheet-style editor support -------------------------------------

    /**
     * Load an entire CSV into memory for in-place editing. Caller should
     * invoke from a background dispatcher; medium-sized logs (~MB) are fine
     * but huge files will block.
     */
    fun loadDatasetFull(file: File): EditableDataset {
        val rows = mutableListOf<List<String>>()
        var headers: List<String> = emptyList()
        try {
            file.bufferedReader().use { br ->
                val first = br.readLine() ?: return EditableDataset(file, emptyList(), emptyList())
                headers = first.split(',').map { it.trim() }
                var line = br.readLine()
                while (line != null) {
                    rows.add(line.split(','))
                    line = br.readLine()
                }
            }
        } catch (e: IOException) {
            Log.w("DataRepository", "Full load failed for ${file.name}", e)
        }
        return EditableDataset(file, headers, rows)
    }

    /**
     * Overwrite [file] with [headers] and [rows]. Writes via a temp file and
     * atomic rename so a crash mid-write can't corrupt the original.
     * Refuses while offline logging is open.
     */
    fun writeDataset(file: File, headers: List<String>, rows: List<List<String>>): Boolean {
        if (isLoggingOffline) return false
        return try {
            val tmp = File(file.parentFile, "${file.nameWithoutExtension}.tmp.csv")
            FileWriter(tmp).use { w ->
                w.append(headers.joinToString(",")).append('\n')
                rows.forEach { row -> w.append(row.joinToString(",")).append('\n') }
            }
            if (file.exists()) file.delete()
            tmp.renameTo(file)
        } catch (e: IOException) {
            Log.e("DataRepository", "writeDataset ${file.name} failed", e); false
        }
    }

    /**
     * Save [rows] under a fresh file in the datasets directory. [baseName]
     * may omit `.csv`; characters outside [A-Za-z0-9._-] are sanitised. If a
     * file with that name already exists, a `_2`, `_3`, … suffix is appended.
     * Returns the new file, or null on IO error.
     */
    fun writeDatasetAs(
        baseName: String,
        headers: List<String>,
        rows: List<List<String>>,
    ): File? {
        val safe = baseName.trim()
            .removeSuffix(".csv")
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .ifEmpty { "snippet_${getDateString()}" }
        var target = File(datasetsDir(), "$safe.csv")
        var n = 2
        while (target.exists()) { target = File(datasetsDir(), "${safe}_$n.csv"); n++ }
        return if (writeDataset(target, headers, rows)) target else null
    }

    /**
     * Upload a single dataset to Edge Impulse tagged with [label]. When
     * [deleteAfter] is true the file is removed on a successful 2xx response.
     * Reports progress via the returned suspend result.
     */
    suspend fun uploadDataset(file: File, label: String, deleteAfter: Boolean): Result<Unit> {
        if (file.extension == "senml") {
            return withContext(Dispatchers.IO) {
                postSenmlAsIngestion(file, label).onSuccess {
                    if (deleteAfter) file.delete()
                }
            }
        }
        return try {
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("data", file.name,
                    file.asRequestBody("text/csv".toMediaType()))
                .build()
            val req = Request.Builder()
                .url("https://ingestion.edgeimpulse.com/api/training/files")
                .header("x-api-key", apiKeyStore.get())
                .header("x-label", label)
                .post(body)
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return Result.failure(IOException("HTTP ${resp.code}: ${resp.body?.string()}"))
                }
                if (deleteAfter) file.delete()
                Result.success(Unit)
            }
        } catch (e: IOException) {
            Result.failure(e)
        }
    }

    private fun uploadFile(file: File, label: String) {
        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("data", file.name, file.asRequestBody("text/csv".toMediaType()))
            .build()

        val request = Request.Builder()
            .url("https://ingestion.edgeimpulse.com/api/training/files")
            .header("x-api-key", apiKeyStore.get())
            .header("x-label", label)
            .post(requestBody)
            .build()

        try {
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.e("DataRepository", "Failed to upload ${file.name}: ${response.body?.string()}")
            } else {
                Log.d("DataRepository", "Successfully uploaded ${file.name}")
            }
        } catch (e: IOException) {
            Log.e("DataRepository", "Upload failed for ${file.name}", e)
        }
    }

    private fun getDateString(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())

    // -------------------------------------------------------------------------
    // Zephyr BLE relay
    // -------------------------------------------------------------------------

    /** Buffer that holds the most recent Zephyr sensor window for correlation. */
    private val pendingZephyrSensorData = mutableListOf<FloatArray>()

    /** True while a user-initiated Nesso recording window is open. */
    @Volatile private var isZephyrRecording = false

    // ---- USB OTG serial recording buffer ------------------------------------
    private val pendingUsbSensorData = mutableListOf<FloatArray>()
    @Volatile private var isUsbRecording = false

    /**
     * Begin a Nesso N1 capture window. Clears any buffered samples so the
     * upload contains only data from this window.
     */
    fun startZephyrRecording() {
        synchronized(pendingZephyrSensorData) {
            pendingZephyrSensorData.clear()
        }
        isZephyrRecording = true
    }

    /**
     * Close the current Nesso N1 capture window and upload the buffered
     * samples to Edge Impulse tagged with [label]. [intervalMs] should match
     * the firmware sampling interval (default 10 ms / 100 Hz).
     */
    fun stopZephyrRecordingAndUpload(label: String, intervalMs: Int = 10) {
        isZephyrRecording = false
        val windows: List<FloatArray>
        synchronized(pendingZephyrSensorData) {
            if (pendingZephyrSensorData.isEmpty()) {
                Log.w("DataRepository", "Nesso recording for '$label' is empty — skipping upload")
                return
            }
            windows = pendingZephyrSensorData.toList()
            pendingZephyrSensorData.clear()
        }
        val rows: List<List<Float>> = windows.map { it.toList() }

        val sensors = listOf(SensorInfo("Nesso N1 IMU", listOf(1000.0 / intervalMs), 600))
        val payload = IngestionPayload(
            device_name  = "nesso-n1",
            device_type  = "NESSO_N1_IMU",
            interval_ms  = intervalMs,
            sensors      = sensors,
            values       = rows
        )
        val requestBody = IngestionRequest(Protected("v1", "none", "00"), payload)

        val request = Request.Builder()
            .url("https://ingestion.edgeimpulse.com/api/training/data")
            .header("x-api-key", apiKeyStore.get())
            .header("x-label", label)
            .post(gson.toJson(requestBody).toRequestBody("application/json".toMediaType()))
            .build()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val response = client.newCall(request).execute()
                if (!response.isSuccessful) {
                    Log.e("DataRepository",
                        "Nesso recording upload failed: ${response.body?.string()}")
                } else {
                    Log.d("DataRepository",
                        "Nesso recording '$label' (${rows.size} samples) uploaded")
                }
            } catch (e: IOException) {
                Log.e("DataRepository", "Nesso recording upload exception", e)
            }
        }
    }

    fun saveZephyrInferenceResult(result: ZephyrInferenceResult) {
        // Build a sensor payload from the buffered raw sensor windows. If no sensor
        // data has been received yet, skip the upload entirely — uploading the
        // confidence value as a fake feature would poison the dataset.
        if (pendingZephyrSensorData.isEmpty()) {
            Log.w("DataRepository", "No sensor data buffered for inference '${result.label}'; skipping upload")
            return
        }
        val sensorWindows: List<List<Float>> =
            pendingZephyrSensorData.map { it.toList() }.also { pendingZephyrSensorData.clear() }

        val sensors = listOf(SensorInfo("Zephyr IMU", listOf(100), 600))
        val payload = IngestionPayload(
            device_name  = "zephyr-ei-monitor",
            device_type  = "ZEPHYR_IMU",
            interval_ms  = 10,
            sensors      = sensors,
            values       = sensorWindows
        )
        val requestBody = IngestionRequest(Protected("v1", "none", "00"), payload)

        val request = Request.Builder()
            .url("https://ingestion.edgeimpulse.com/api/training/data")
            .header("x-api-key", apiKeyStore.get())
            .header("x-label", result.label)
            .post(gson.toJson(requestBody).toRequestBody("application/json".toMediaType()))
            .build()

        try {
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.e("DataRepository", "Zephyr inference upload failed: ${response.body?.string()}")
            } else {
                Log.d("DataRepository", "Zephyr inference '${result.label}' uploaded")
            }
        } catch (e: IOException) {
            Log.e("DataRepository", "Zephyr inference upload exception", e)
        }
    }

    fun saveZephyrSensorData(samples: FloatArray) {
        synchronized(pendingZephyrSensorData) {
            pendingZephyrSensorData.add(samples)
        }
        if (isLoggingOffline) {
            if (offlineFormat == OfflineLogFormat.SENML) {
                appendOfflineSenmlLine(System.currentTimeMillis(),
                    List(samples.size) { "zephyr_$it" }, samples)
            } else try {
                logFileWriter?.append(samples.joinToString(",") + "\n")
            } catch (e: IOException) {
                Log.e("DataRepository", "CSV write error", e)
            }
        }
    }

    // -------------------------------------------------------------------------
    // USB OTG serial recording
    // -------------------------------------------------------------------------

    /**
     * Begin a USB serial capture window. Clears any buffered samples so the
     * upload contains only data from this window.
     */
    fun startUsbRecording() {
        synchronized(pendingUsbSensorData) {
            pendingUsbSensorData.clear()
        }
        isUsbRecording = true
    }

    /**
     * Close the USB capture window and upload buffered samples to Edge Impulse
     * tagged with [label]. [intervalMs] should match the Arduino sketch's
     * sampling interval (default 10 ms / 100 Hz). [columnHeaders] are the
     * column names declared by the firmware's `!header` line.
     */
    fun stopUsbRecordingAndUpload(
        label: String,
        intervalMs: Int = 10,
        columnHeaders: List<String> = emptyList(),
    ) {
        isUsbRecording = false
        val windows: List<FloatArray>
        synchronized(pendingUsbSensorData) {
            if (pendingUsbSensorData.isEmpty()) {
                Log.w("DataRepository", "USB recording '$label' is empty — skipping upload")
                return
            }
            windows = pendingUsbSensorData.toList()
            pendingUsbSensorData.clear()
        }
        val rows: List<List<Float>> = windows.map { it.toList() }
        val sensorName = if (columnHeaders.isNotEmpty()) columnHeaders.joinToString("/") else "USB IMU"
        val sensors = listOf(SensorInfo(sensorName, listOf(1000.0 / intervalMs), 600))
        val payload = IngestionPayload(
            device_name = "usb-serial-device",
            device_type = "USB_SERIAL_IMU",
            interval_ms = intervalMs,
            sensors     = sensors,
            values      = rows
        )
        val requestBody = IngestionRequest(Protected("v1", "none", "00"), payload)
        val request = Request.Builder()
            .url("https://ingestion.edgeimpulse.com/api/training/data")
            .header("x-api-key", apiKeyStore.get())
            .header("x-label", label)
            .post(gson.toJson(requestBody).toRequestBody("application/json".toMediaType()))
            .build()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val response = client.newCall(request).execute()
                if (!response.isSuccessful) {
                    Log.e("DataRepository", "USB recording upload failed: ${response.body?.string()}")
                } else {
                    Log.d("DataRepository", "USB recording '$label' (${rows.size} samples) uploaded")
                }
            } catch (e: IOException) {
                Log.e("DataRepository", "USB recording upload exception", e)
            }
        }
    }

    /**
     * Store one USB serial sample. Called continuously by [UsbSerialClient] as
     * data arrives; only buffered when a recording window is open.
     * [columnNames]/[columnUnits] (from the firmware's `!header` line or its
     * SenML records) enrich SenML-format offline logs; CSV logging and the
     * recording buffer ignore them.
     */
    fun saveUsbSensorData(
        samples: FloatArray,
        columnNames: List<String>? = null,
        columnUnits: List<String?>? = null,
    ) {
        if (isUsbRecording) {
            synchronized(pendingUsbSensorData) {
                pendingUsbSensorData.add(samples)
            }
        }
        if (isLoggingOffline) {
            if (offlineFormat == OfflineLogFormat.SENML) {
                appendOfflineSenmlLine(
                    System.currentTimeMillis(),
                    columnNames ?: List(samples.size) { "col_$it" },
                    samples,
                    columnUnits,
                )
            } else try {
                logFileWriter?.append(samples.joinToString(",") + "\n")
            } catch (e: IOException) {
                Log.e("DataRepository", "USB CSV write error", e)
            }
        }
    }

    // -------------------------------------------------------------------------
    // Camera / image upload
    // -------------------------------------------------------------------------

    /**
     * Upload a raw JPEG [imageBytes] to Edge Impulse ingestion as a training image.
     * [label] is the EI data label (e.g. "normal", "anomaly").
     */
    suspend fun uploadImage(imageBytes: ByteArray, label: String): Boolean {
        val multipart = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "data",
                "image_${System.currentTimeMillis()}.jpg",
                imageBytes.toRequestBody("image/jpeg".toMediaType())
            )
            .build()
        val request = Request.Builder()
            .url("https://ingestion.edgeimpulse.com/api/training/files")
            .header("x-api-key", apiKeyStore.get())
            .header("x-label", label)
            .post(multipart)
            .build()

        return try {
            val response = withContext(Dispatchers.IO) { client.newCall(request).execute() }
            if (!response.isSuccessful) {
                Log.e("DataRepository", "Image upload failed: ${response.body?.string()}")
            } else {
                Log.d("DataRepository", "Image uploaded with label='$label'")
            }
            response.isSuccessful
        } catch (e: IOException) {
            Log.e("DataRepository", "Image upload exception", e)
            false
        }
    }

    // -------------------------------------------------------------------------
    // Microphone / audio upload
    // -------------------------------------------------------------------------

    /**
     * Upload a raw WAV [wavBytes] clip to Edge Impulse ingestion. Use this
     * for one-shot microphone captures (16 kHz mono PCM16 is the standard
     * EI audio format).
     */
    fun uploadAudio(wavBytes: ByteArray, label: String) {
        val multipart = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "data",
                "audio_${System.currentTimeMillis()}.wav",
                wavBytes.toRequestBody("audio/wav".toMediaType())
            )
            .build()
        val request = Request.Builder()
            .url("https://ingestion.edgeimpulse.com/api/training/files")
            .header("x-api-key", apiKeyStore.get())
            .header("x-label", label)
            .post(multipart)
            .build()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val response = client.newCall(request).execute()
                if (!response.isSuccessful) {
                    Log.e("DataRepository", "Audio upload failed: ${response.body?.string()}")
                } else {
                    Log.d("DataRepository", "Audio uploaded with label='$label'")
                }
            } catch (e: IOException) {
                Log.e("DataRepository", "Audio upload exception", e)
            }
        }
    }

    // -------------------------------------------------------------------------
    // Unified multi-modal capture (phone sensors + Wear OS samples)
    // -------------------------------------------------------------------------

    /** Per-sensor sample buffer keyed by canonical sensor key (e.g. "accel"). */
    private data class SensorBuffer(
        val key: String,
        val values: MutableList<FloatArray> = mutableListOf(),
    )

    private val phoneBuffers = mutableMapOf<String, SensorBuffer>()
    private val wearBuffers  = mutableMapOf<String, SensorBuffer>()
    @Volatile private var isMultiRecording = false

    /** Start a unified capture window. Clears all per-sensor buffers. */
    fun startMultiRecording() {
        synchronized(phoneBuffers) { phoneBuffers.clear() }
        synchronized(wearBuffers)  { wearBuffers.clear()  }
        isMultiRecording = true
    }

    /** Append one phone-sensor sample to the unified buffer. */
    fun appendPhoneSample(sensorKey: String, values: FloatArray) {
        if (!isMultiRecording) return
        synchronized(phoneBuffers) {
            phoneBuffers.getOrPut(sensorKey) { SensorBuffer(sensorKey) }
                .values.add(values)
        }
    }

    /** Append one Wear OS sample (called from message route). */
    fun appendWearSamples(sensorKey: String, @Suppress("UNUSED_PARAMETER") timestampMs: Long,
                          values: FloatArray) {
        if (!isMultiRecording) return
        synchronized(wearBuffers) {
            wearBuffers.getOrPut(sensorKey) { SensorBuffer(sensorKey) }
                .values.add(values)
        }
    }

    /**
     * Close the unified window and upload each buffered sensor stream as
     * its own Edge Impulse ingestion sample. All samples share [label] so
     * they line up in the dataset and can be combined downstream.
     */
    fun stopMultiRecordingAndUpload(label: String, durationMs: Long) {
        isMultiRecording = false
        val phoneSnap: Map<String, List<FloatArray>>
        val wearSnap:  Map<String, List<FloatArray>>
        synchronized(phoneBuffers) {
            phoneSnap = phoneBuffers.mapValues { it.value.values.toList() }
            phoneBuffers.clear()
        }
        synchronized(wearBuffers) {
            wearSnap = wearBuffers.mapValues { it.value.values.toList() }
            wearBuffers.clear()
        }
        if (phoneSnap.isEmpty() && wearSnap.isEmpty()) {
            Log.w("DataRepository", "Multi-recording '$label' empty — skipping upload")
            return
        }
        CoroutineScope(Dispatchers.IO).launch {
            phoneSnap.forEach { (key, rows) ->
                if (rows.isNotEmpty()) {
                    uploadStream("phone-$key", "ANDROID_PHONE_$key".uppercase(),
                                 rows, label, durationMs)
                }
            }
            wearSnap.forEach { (key, rows) ->
                if (rows.isNotEmpty()) {
                    uploadStream("wear-$key", "WEAROS_$key".uppercase(),
                                 rows, label, durationMs)
                }
            }
        }
    }

    private fun uploadStream(
        sensorName: String,
        deviceType: String,
        rows: List<FloatArray>,
        label: String,
        durationMs: Long,
    ) {
        // Derive a per-sample interval from the actual sample count over the
        // capture window. Edge Impulse uses this to plot the time axis.
        val intervalMs = if (rows.size > 1) (durationMs.toDouble() / rows.size) else 10.0
        val freqHz     = if (intervalMs > 0) 1000.0 / intervalMs else 100.0
        val maxLenS    = ((durationMs / 1000).coerceAtLeast(1)).toInt()

        val sensors = listOf(SensorInfo(sensorName, listOf(freqHz), maxLenS))
        val values  = rows.map { it.toList() }
        val payload = IngestionPayload(deviceId, deviceType, intervalMs, sensors, values)
        val body    = IngestionRequest(Protected("v1", "none", "00"), payload)

        val request = Request.Builder()
            .url("https://ingestion.edgeimpulse.com/api/training/data")
            .header("x-api-key", apiKeyStore.get())
            .header("x-label", label)
            .post(gson.toJson(body).toRequestBody("application/json".toMediaType()))
            .build()
        try {
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.e("DataRepository",
                    "Multi upload '$sensorName' failed: ${response.body?.string()}")
            } else {
                Log.d("DataRepository",
                    "Multi upload '$sensorName' (${rows.size} samples) ok")
            }
        } catch (e: IOException) {
            Log.e("DataRepository", "Multi upload '$sensorName' exception", e)
        }
    }
}
