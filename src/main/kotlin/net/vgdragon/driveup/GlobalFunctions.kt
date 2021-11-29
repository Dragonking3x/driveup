package net.vgdragon.driveup

import com.google.gson.Gson
import java.io.File

val dataClassFileName = "data.json"

fun saveDataClass(data: DataClass) {
    val json = Gson().toJson(data)
    val file = File(dataClassFileName)
    file.writeText(json)
}
fun loadDataClass(): DataClass {
    val file = File(dataClassFileName)
    if (!file.exists()) {
        saveDataClass(DataClass())
    }
    val json = file.readText()
    return Gson().fromJson(json, DataClass::class.java)
}