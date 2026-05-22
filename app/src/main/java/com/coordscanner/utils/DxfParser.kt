package com.coordscanner.utils

import com.coordscanner.model.WayPoint
import java.io.InputStream

object DxfParser {

    fun parse(stream: InputStream): List<WayPoint> {
        val lines = stream.bufferedReader().readLines()
        val points = mutableListOf<WayPoint>()
        var i = 0
        var inEntities = false

        while (i < lines.size - 1) {
            val code  = lines[i].trim()
            val value = lines[i + 1].trim()

            when {
                // Вход в секцию ENTITIES
                code == "2" && value.equals("ENTITIES", ignoreCase = true) -> {
                    inEntities = true
                    i += 2
                }
                // Конец секции
                code == "0" && value.equals("ENDSEC", ignoreCase = true) -> {
                    if (inEntities) break
                    i += 2
                }
                // Объект POINT внутри ENTITIES
                inEntities && code == "0" && value.equals("POINT", ignoreCase = true) -> {
                    i += 2
                    var lon = 0.0; var lat = 0.0; var name = ""
                    while (i < lines.size - 1) {
                        val c = lines[i].trim()
                        val v = lines[i + 1].trim()
                        if (c == "0") break  // начало следующего объекта
                        when (c) {
                            "10" -> lon  = v.toDoubleOrNull() ?: lon
                            "20" -> lat  = v.toDoubleOrNull() ?: lat
                            "1"  -> name = v
                        }
                        i += 2
                    }
                    if (lat != 0.0 || lon != 0.0) {
                        points.add(WayPoint(
                            lat  = lat,
                            lon  = lon,
                            name = name.ifBlank { "Точка ${points.size + 1}" }
                        ))
                    }
                    // i уже указывает на следующий "0" — не инкрементировать
                }
                else -> i += 2
            }
        }
        return points
    }
}
