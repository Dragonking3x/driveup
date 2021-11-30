package net.vgdragon.driveup

import com.google.api.services.drive.Drive
import com.google.api.services.drive.model.File as GoogleFile
import java.io.File
import java.util.*

enum class FileBackupType {
    BY_MODIFIED_DATE
}

fun FileBackup(dataClass: DataClass,
               googleDriveService: Drive,
               backupFolder: String,
               googleDriveFolder: String = "") = FileBackup(dataClass,
                                                            googleDriveService,
                                                            File(backupFolder),
                                                            googleDriveFolder)


class FileBackup (val dataClass: DataClass,
                  val googleDriveService: Drive,
                  val backupFolder: File,
                  val googleDriveFolder: String = ""){

    fun startRoutine(timeBetweenBackups: Long = 1000 * 60 * 60 * 24 * 7,
                     backupType: FileBackupType = FileBackupType.BY_MODIFIED_DATE) {

        val backupThread = Thread {
            var lastBackup: Long = Long.MAX_VALUE
            while (true) {
                for (backupData in this.dataClass.backupFolderMap.values){
                    var saveData = false
                    if (backupData.lastBackup + timeBetweenBackups < System.currentTimeMillis()){
                        backupFiles(backupFolder, googleDriveFolder, backupData.lastBackup, backupType)
                        backupData.lastBackup = System.currentTimeMillis()
                        saveData = true
                    }
                    if(backupData.lastBackup < lastBackup){
                        lastBackup = backupData.lastBackup
                    }
                    if(saveData){
                        saveDataClass(dataClass)
                    }
                }
                sleepUntil(lastBackup + timeBetweenBackups)
            }
        }
        backupThread.start()
    }

    fun backupFiles(backupFolder: File,
                    googleDriveFolder: String,
                    lastBackup: Long = 0,
                    backupType: FileBackupType = FileBackupType.BY_MODIFIED_DATE){
        if (backupFolder.listFiles() == null) {
            return
        }
        val fileList = if (backupFolder.listFiles() == null) {
            return
        } else {
            backupFolder.listFiles()
        }
        for (file in fileList){
            deepSearchBackup(file, googleDriveFolder, lastBackup, backupType)
        }
    }

    fun deepSearchBackup(backupFolder: File,
                         googleDriveFolder: String,
                         lastBackup: Long = 0,
                         backupType: FileBackupType = FileBackupType.BY_MODIFIED_DATE){
        val fileList = if (backupFolder.listFiles() == null) {
            return
        } else {
            backupFolder.listFiles()
        }
        for (file in fileList) {
            if (file.isDirectory) {
                deepSearchBackup(file, googleDriveFolder + file.name + "/", lastBackup)
            } else {
                val driveFile = googleDriveService.files().get(googleDriveFolder + file.name).execute()

                if (isNeedBackup(driveFile, file, backupType)) {
                    googleDriveService.files().update(driveFile.id, null).execute()
                }
            }

        }
    }

    fun firstPreparation(){
        val googleFileList: MutableList<GoogleFile> = mutableListOf()
        var pageToken: String? = null
        var pageInt = 0
        println("----- Start Loading Google Drive Files Data -----")
        println("Start Date: " + Date())
        val startTime = System.currentTimeMillis()
        var pageStartTime = System.currentTimeMillis()
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
            val duration = System.currentTimeMillis() - pageStartTime
            val minutes = ((duration / 1000L) / 60L).toInt()
            val seconds = ((duration / 1000L) % 60L).toInt()
            val milliseconds = (duration % 1000L).toInt()
            println("Page $pageInt : $minutes Minutes " +
                    "${seconds}.${milliseconds} Seconds")
            pageStartTime = System.currentTimeMillis()
        } while (pageToken != null)
        val timeUntilCompleted = System.currentTimeMillis() - startTime
        println("----- Finished Loading Google Drive Files Data -----")
        val minutes = (timeUntilCompleted / (1000L * 60L)).toInt()
        val seconds = ((timeUntilCompleted / 1000L) % 60L).toInt()
        val milliseconds = (timeUntilCompleted % 1000L).toInt()
        println("Finished in: $minutes Minutes " +
                "${seconds}.${milliseconds} Seconds")

        println("End Date: " + Date())

        println("----- Start Sorting GoogleFiles -----")
        var backupClass = dataClass.backupFolderMap.get(googleDriveFolder)
        if(backupClass == null){
            backupClass = BackUpDataClass(backupFolder.absolutePath, googleDriveFolder)
            dataClass.backupFolderMap.put(googleDriveFolder, backupClass)
        }
        val fileList: MutableList<FileDataClass> = mutableListOf()
        for (googleFile in googleFileList){

            fileList.add(
                FileDataClass(googleFile.name ?: "",
                        googleFile.id ?: "",
                        "",
                        googleFile.mimeType ?: "",
                        googleFile.modifiedTime.value ?: 0L,
                        googleFile.createdTime.value ?: 0L,
                        googleFile.originalFilename ?: "",
                        googleFile.fileExtension ?: "",
                        googleFile.md5Checksum ?: "",
                    googleFile.mimeType == "application/vnd.google-apps.folder",
                        googleFile.parents ?: mutableListOf()
                )
            )
        }
        backupClass.googleFileDataClass = FileDataClass(fileList = fileList)
        val baseGoogleFolder = sortGoogleFiles(backupClass.googleFileDataClass.fileList)
        backupClass.googleFileDataClass = baseGoogleFolder
        saveDataClass(dataClass)

        println("----- End Sorting GoogleFiles -----")
        println()
    }

    fun sortGoogleFiles(googleFileList: MutableList<FileDataClass>) : FileDataClass{
        val sortedGoogleFileList = mutableListOf<FileDataClass>()
        val folderList = mutableListOf<FileDataClass>()
        val fileList = mutableListOf<FileDataClass>()
        for(googleFile in googleFileList){
            if(googleFile.mimeType == "application/vnd.google-apps.folder"){
                folderList.add(googleFile)
            } else {
                fileList.add(googleFile)
            }
        }
        for(folder in folderList){
            sortedGoogleFileList.add(folder)
            for(file in fileList){
                if(file.parents.contains(folder.id)){
                    sortedGoogleFileList.add(file)
                }
            }
        }
        folderList.clear()
        folderList.addAll(sortedGoogleFileList)
        sortedGoogleFileList.clear()

        for(googleFile in fileList){
            val googleFolder = folderList.find { it.id == googleFile.parents[0] }
            if(googleFolder != null){
                googleFolder.fileList.add(googleFile)
            } else {
                folderList.add(googleFile)
            }
        }

        //sortedGoogleFileList.addAll(folderList)
        //sortedGoogleFileList.addAll(fileList)
        //dataClass.googleFiles = sortedGoogleFileList
        //saveDataClass(dataClass)
        return googleFolderSorter(folderList)
    }
    fun googleFolderSorter(googleFolderList: MutableList<FileDataClass>): FileDataClass{
        while (googleFolderList.size > 1){
            val folder = googleFolderList.removeAt(0)
            val folderParent = googleFolderList.find { it.id == folder.parents[0] }
            if(folderParent != null){
                folderParent.fileList.add(folder)
            } else {
                googleFolderList.add(folder)
            }
        }
        return googleFolderList[0]
    }

    fun isNeedBackup(driveFile: GoogleFile,
                     localFile: File,
                     backupType: FileBackupType): Boolean{

        when(backupType){
            FileBackupType.BY_MODIFIED_DATE -> return driveFile.modifiedTime.value < localFile.lastModified()
        }
    }




    fun sleepUntil(date: Date){
        sleepUntil(date.time)
    }
    private fun sleepUntil(time: Long){
        while (System.currentTimeMillis() < time) {
            Thread.sleep(1000)
        }
    }

}