package net.vgdragon

import net.vgdragon.driveup.*
import java.io.File
import java.util.*
import com.google.api.services.drive.model.File as GoogleFile

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

    //val file = File(localFolderPath)
    /*
    val googleFileList: MutableList<GoogleFile> = mutableListOf()
    var pageToken: String? = null
    var pageInt = 0
    println(Date())
    val startTime = System.currentTimeMillis()
    var lastTime = System.currentTimeMillis()
    do {
        val execute = googleDriveService
            .files()
            .list()
            .setPageSize(1000)
            .setPageToken(pageToken)
            .setFields("nextPageToken, files(id, name, mimeType, parents, modifiedTime, createdTime, originalFilename, fileExtension, md5Checksum, parents)")
            .execute()
        //mimeType = application/vnd.google-apps.folder
        googleFileList.addAll(execute.files)
        pageToken = execute.nextPageToken
        pageInt++
        val duration = System.currentTimeMillis() - lastTime
        val minutes = ((duration / 1000L) / 60L).toInt()
        val seconds = ((duration / 1000L) % 60L).toInt()
        val milliseconds = (duration % 1000L).toInt()
        println("$pageInt : $minutes Minutes " +
                "${seconds}.${milliseconds} Seconds")
        lastTime = System.currentTimeMillis()
    } while (pageToken != null)
    val timeUntilCompleted = System.currentTimeMillis() - startTime
    println("------------------------------------------------------")
    val minutes = (timeUntilCompleted / (1000L * 60L)).toInt()
    val seconds = ((timeUntilCompleted / 1000L) % 60L).toInt()
    val milliseconds = (timeUntilCompleted % 1000L).toInt()
    println("Finished in: $minutes Minutes " +
            "${seconds}.${milliseconds} Seconds")
    println(Date())
    dataClass.googleFiles = googleFileList
    saveDataClass(dataClass)

*/

    val ignoringFileList: MutableList<String> = mutableListOf()
    ignoringFileList.add("Thumbs.db")
    ignoringFileList.add("desktop.ini")
    ignoringFileList.add(".tmp.drivedownload")
    ignoringFileList.add(".tmp.driveupload")
    ignoringFileList.add("System Volume Information")



    println()
    val fileBackup = FileBackup(dataClass,
        googleDriveService,
        localFolderPath,
        googleDriveFolder,
        ignoringFileList = ignoringFileList,
        fileUpdateDirectionType = FileUpdateDirectionType.BOTH)
    //val findGoogleFolder = fileBackup.findGoogleFolder("manu", googleDriveFolder)
    fileBackup.firstPreparation()
    //fileBackup.firstPreparation()
    // 1 minute
    //fileBackup.startRoutine(60000, FileBackupType.BY_MODIFIED_DATE)
    println()


}

