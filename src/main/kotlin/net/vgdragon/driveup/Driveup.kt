package net.vgdragon

import net.vgdragon.driveup.*

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

    //val localFolderPath = args[0]
    //val googleDriveFolder = args[1]

    val localFolderPath = "/home/dragonking3x/Ubuntu_backup"
    val googleDriveFolder = "/"

    val fileBackup = FileBackup(loadDataClass(), googleDriveService, localFolderPath, googleDriveFolder)
    // 1 minute
    fileBackup.startRoutine(60000, FileBackupType.BY_MODIFIED_DATE)


}

