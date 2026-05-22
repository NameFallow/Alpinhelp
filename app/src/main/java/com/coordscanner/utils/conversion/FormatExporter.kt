package com.coordscanner.utils.conversion

import com.coordscanner.model.WayPoint
import java.io.OutputStream

interface FormatExporter {
    fun export(points: List<WayPoint>, out: OutputStream)
}
