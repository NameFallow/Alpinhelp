package com.coordscanner.utils

import com.coordscanner.model.WayPoint
import java.io.InputStream

object DxfParser {

    fun parse(stream: InputStream): List<WayPoint> {
        val lines = stream.bufferedReader(Charsets.UTF_8).readLines()
        val points = mutableListOf<WayPoint>()
        var i = 0
        var inEntities = false

        while (i + 1 < lines.size) {
            val code  = lines[i].trim()
            val value = lines[i + 1].trim()

            when {
                code == "2" && value.equals("ENTITIES", ignoreCase = true) -> {
                    inEntities = true
                    i += 2
                }
                code == "0" && value.equals("ENDSEC", ignoreCase = true) -> {
                    if (inEntities) break
                    i += 2
                }
                inEntities && code == "0" && value.equals("POINT", ignoreCase = true) -> {
                    i += 2
                    // DXF: код 10 = X = longitude, код 20 = Y = latitude
                    var lon = 0.0; var lat = 0.0; var name = ""
                    while (i + 1 < lines.size) {
                        val c = lines[i].trim()
                        val v = lines[i + 1].trim()
                        if (c == "0") break
                        when (c) {
                            // Защита от запятой как десятичного разделителя
                            "10" -> lon  = v.replace(",", ".").toDoubleOrNull() ?: lon
                            "20" -> lat  = v.replace(",", ".").toDoubleOrNull() ?: lat
                            "1"  -> name = v
                        }
                        i += 2
                    }
                    if (lat != 0.0 || lon != 0.0) {
                        points.add(WayPoint(
                            name = name.ifBlank { "Точка ${points.size + 1}" },
                            lat  = lat,
                            lon  = lon
                        ))
                    }
                    // i уже на следующем "0" — не инкрементировать
                }
                else -> i += 2
            }
        }
        return points
    }
}
