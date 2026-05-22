package com.coordscanner.utils.conversion

import com.coordscanner.model.WayPoint
import java.io.InputStream

interface FormatParser {
    fun parse(stream: InputStream): List<WayPoint>
}
