package net.vgdragon

import com.google.api.client.http.GenericUrl
import com.google.gson.internal.bind.TypeAdapters.URL
import net.vgdragon.driveup.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.*


fun main(args: Array<String>) {
    val googleDriveService = GoogleDriveController().build()
    if(googleDriveService == null) {
        println("Google Drive Service is not available")
        return
    }
    if (args.size < 1) {
        println("Usage: driveup <folder>")
        return
    }

    var localFolderPath = args[0]
    var googleDriveFolder = args[1]


    val dataClass = loadDataClass()


    val ignoringFileList: MutableList<String> = mutableListOf()
    ignoringFileList.add("Thumbs.db")
    ignoringFileList.add("desktop.ini")
    ignoringFileList.add(".tmp.drivedownload")
    ignoringFileList.add(".tmp.driveupload")
    ignoringFileList.add("System Volume Information")

    val fileBackup = FileBackup(dataClass,
        googleDriveService,
        localFolderPath,
        googleDriveFolder,
        ignoringFileList = ignoringFileList,
        fileUpdateDirectionType = FileUpdateDirectionType.BOTH)


    fileBackup.firstPreparation()
    // 1 minute
    //fileBackup.startRoutine(60000, FileBackupType.BY_MODIFIED_DATE)
    println()


}

