package net.vgdragon.driveup

import com.google.api.services.drive.Drive
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
        for (file in fileList){
            if(file.lastModified() > lastBackup){
                if(file.isDirectory){
                    deepSearchBackup(file, googleDriveFolder + file.name + "/", lastBackup)
                } else{
                    val driveFile = googleDriveService.files().get(googleDriveFolder + file.name).execute()

                    if(isNeedBackup(driveFile, file, backupType)){
                        googleDriveService.files().update(driveFile.id, null).execute()
                    }
                }
            }
        }
    }

    fun isNeedBackup(driveFile: com.google.api.services.drive.model.File,
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