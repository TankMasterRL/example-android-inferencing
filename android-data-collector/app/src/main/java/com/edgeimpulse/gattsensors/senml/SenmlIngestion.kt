package com.edgeimpulse.gattsensors.senml

import com.edgeimpulse.gattsensors.IngestionPayload
import com.edgeimpulse.gattsensors.SensorInfo

/**
 * Converts line-delimited SenML (one RFC 8428 pack per line, as written by
 * the offline logger) into an Edge Impulse data-acquisition payload at flush
 * time — the ingestion API does not accept SenML natively. Pure function; the
 * HTTP POST stays in DataRepository.
 */
object SenmlIngestion {

    data class Conversion(
        val payload: IngestionPayload,
        /** Lines dropped: malformed JSON (incl. a crash-truncated final line),
         *  no numeric values, or a channel set differing from the first line. */
        val skippedLines: Int,
    )

    /**
     * Each parseable line becomes one sample row. Channel order is fixed by
     * the first valid line's record names; later lines are matched by name
     * and must carry the same channels in the same order (Edge Impulse needs
     * a rectangular matrix with stable channel order). `vs`/`vb` records are
     * tolerated but excluded from the numeric matrix.
     *
     * `interval_ms` comes from the resolved time span across samples
     * (deltas only — device base times are often boot-relative, never
     * trusted as wall-clock), falling back to [defaultIntervalMs] for
     * single-sample files or non-increasing times.
     *
     * Returns null when no line yields a valid sample.
     */
    fun convert(
        lines: Sequence<String>,
        fallbackDeviceName: String,
        defaultIntervalMs: Double = 10.0,
    ): Conversion? {
        var channels: List<String>? = null
        var deviceName: String? = null
        val rows = mutableListOf<List<Float>>()
        val times = mutableListOf<Double>()
        var skipped = 0

        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val rawRecords = try {
                Senml.fromJson(line)
            } catch (e: RuntimeException) {
                skipped++
                continue
            }
            if (deviceName == null) {
                deviceName = rawRecords.firstNotNullOfOrNull { it.bn }
                    ?.trimEnd(':', '/')
                    ?.takeIf { it.isNotEmpty() }
            }
            val resolved = Senml.resolve(rawRecords)
            // First occurrence wins on duplicate names within one pack.
            val byName = LinkedHashMap<String, Double>()
            for (r in resolved) {
                val v = r.value ?: continue
                if (!byName.containsKey(r.shortName)) byName[r.shortName] = v
            }
            if (byName.isEmpty()) {
                skipped++
                continue
            }
            val names = byName.keys.toList()
            if (channels == null) channels = names
            if (names != channels) {
                skipped++
                continue
            }
            rows.add(names.map { byName.getValue(it).toFloat() })
            times.add(resolved.first { it.value != null }.time)
        }

        val chans = channels ?: return null
        if (rows.isEmpty()) return null

        val span = times.last() - times.first()
        val intervalMs = (if (rows.size >= 2 && span > 0.0 && span.isFinite()) {
            span / (rows.size - 1) * 1000.0
        } else {
            defaultIntervalMs
        }).coerceAtLeast(1.0)

        val sensors = chans.map { SensorInfo(it, listOf(1000.0 / intervalMs), 600) }
        return Conversion(
            payload = IngestionPayload(
                device_name = deviceName ?: fallbackDeviceName,
                device_type = "SENML_LOG",
                interval_ms = intervalMs,
                sensors     = sensors,
                values      = rows,
            ),
            skippedLines = skipped,
        )
    }
}
