package net.vgdragon.driveup

import com.google.api.client.http.AbstractInputStreamContent
import com.google.api.client.http.FileContent
import com.google.api.services.drive.Drive
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.*
import com.google.api.services.drive.model.File as GoogleFile


enum class FileBackupType {
    BY_MODIFIED_DATE,
    BY_HASH,
    BY_HASH_AND_MODIFIED_DATE
}

enum class FileUpdateDirectionType {
    LOCAL_TO_GOOGLE,
    GOOGLE_TO_LOCAL,
    BOTH,
    NONE
}

fun FileBackup(dataClass: DataClass,
               googleDriveService: Drive,
               backupFolder: String,
               googleDriveFolder: String = "",
               ignoringFileList: MutableList<String> = mutableListOf(),
               backupType: FileBackupType = FileBackupType.BY_MODIFIED_DATE,
               fileUpdateDirectionType: FileUpdateDirectionType = FileUpdateDirectionType.LOCAL_TO_GOOGLE
) = FileBackup(dataClass,
    googleDriveService,
    File(backupFolder),
    googleDriveFolder,
    ignoringFileList,
    backupType,
    fileUpdateDirectionType)


class FileBackup (val dataClass: DataClass,
                  val googleDriveService: Drive,
                  val localBackupFolder: File,
                  val googleDriveFolder: String = "",
                  val ignoringFileList: MutableList<String> = mutableListOf(),
                  val backupType: FileBackupType = FileBackupType.BY_MODIFIED_DATE,
                  val updateDirectionType: FileUpdateDirectionType = FileUpdateDirectionType.LOCAL_TO_GOOGLE) {

    private val threadMap: MutableMap<Long, Thread> = mutableMapOf()
    private val threadMapLock: Object = Object()
    private val activeThreadsMap: MutableMap<Long, Thread> = mutableMapOf()
    private val activeThreadsMapLock: Object = Object()

    val maximalRunningThreads: Int = 5000
    private var currentActionsGoogle = 0
    private val currentActionsGoogleLock: Object = Object()

    private var currentActionsLocal = 0
    private val currentActionsLocalLock: Object = Object()

    /*
    fun startRoutine(timeBetweenBackups: Long = 1000 * 60 * 60 * 24 * 7) {

        val backupThread = Thread {
            var lastBackup: Long = Long.MAX_VALUE
            while (true) {
                for (backupData in this.dataClass.backupFolderMap.values){
                    var saveData = false
                    if (backupData.lastBackup + timeBetweenBackups < System.currentTimeMillis()){
                        backupFiles(localBackupFolder, googleDriveFolder, backupData.lastBackup)
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
                    lastBackup: Long = 0){
        if (backupFolder.listFiles() == null) {
            return
        }
        val fileList = if (backupFolder.listFiles() == null) {
            return
        } else {
            backupFolder.listFiles()
        }
        for (file in fileList){
            deepSearchBackup(file, googleDriveFolder, lastBackup)
        }
    }

    fun deepSearchBackup(backupFolder: File,
                         googleDriveFolder: String,
                         lastBackup: Long = 0){
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


    */

    fun firstPreparation(){
        if(!localBackupFolder.exists()){
            localBackupFolder.mkdirs()
        }
        if(!localBackupFolder.isDirectory){
            println("Local Backup Folder is not a directory")
            return
        }
        val localBackupFileList = if(localBackupFolder.listFiles().isNullOrEmpty()){
            println("Local Backup Folder is empty")
            return
        } else {
            localBackupFolder.listFiles()!!.toMutableList()
        }

        var backupDataClass = dataClass.backupFolderMap[localBackupFolder.absolutePath]
        if (backupDataClass == null) {
            backupDataClass = BackUpDataClass(localBackupFolder.absolutePath, googleDriveFolder)
            dataClass.backupFolderMap[localBackupFolder.absolutePath] = backupDataClass
        }

        val googleRootFileDataClass = FileDataClass(
            "",
            googleDriveFolder,
            "",
            "application/vnd.google-apps.folder",
            0L,
            0L,
            "",
            "",
            "",
            true,
            mutableListOf()
        )

        backupDataClass.localFileDataClass = convertSrcFileToFileDataClass(localBackupFolder)
        backupDataClass.googleFileDataClass = googleRootFileDataClass

        println("----- Start Loading Google Drive Files Data -----")
        println("Start Date: " + Date())

        val startTime = System.currentTimeMillis()
        val googleFileList: MutableList<GoogleFile> = getGoogleDriveFileList(googleDriveFolder)

        println("----- Finished loading root folder-----")
        println("----- Start Loading File Data -----")

        loadGoogleFolderDataInClass(googleFileList, backupDataClass.googleFileDataClass)
        loadLocalFolderDataInClass(localBackupFileList, backupDataClass.localFileDataClass)
        var finished = false

        var lastLineOutput = System.currentTimeMillis()
        while (!finished) {

            synchronized(threadMapLock) {
                synchronized(activeThreadsMapLock) {
                    if(activeThreadsMap.size < maximalRunningThreads && threadMap.isNotEmpty()) {
                        val thread = threadMap.remove(threadMap.keys.first())

                        activeThreadsMap[thread!!.id] = thread
                        thread.start()
                    }
                    synchronized(currentActionsGoogleLock) {
                        synchronized(currentActionsLocalLock) {
                            if (currentActionsGoogle <= 0 && currentActionsLocal <= 0) {
                                finished = true
                            }
                        }
                    }
                }
            }
            if( System.currentTimeMillis() - lastLineOutput > 1000){
                synchronized(currentActionsGoogleLock) {
                    println("Current Actions: ${currentActionsGoogle + currentActionsLocal}")
                }
                lastLineOutput = System.currentTimeMillis()
            }
            Thread.sleep(10)
        }
        val timeUntilCompleted = System.currentTimeMillis() - startTime

        println("----- Finished Loading File Data  -----")
        val minutes = (timeUntilCompleted / (1000L * 60L)).toInt()
        val seconds = ((timeUntilCompleted / 1000L) % 60L).toInt()
        val milliseconds = (timeUntilCompleted % 1000L).toInt()
        println("Finished in: $minutes Minutes " +
                "${seconds}.${milliseconds} Seconds")
        //saveDataClass(dataClass)
        println("----- Start to Compare Files  -----")



        println("----- Finished to Compare Files  -----")

        //updateFiles(backupDataClass) // TODO
        println()
    }



    fun updateFiles(backupDataClass: BackUpDataClass){
        if (updateDirectionType == FileUpdateDirectionType.LOCAL_TO_GOOGLE) {
            updateLocalToGoogle(backupDataClass.localFileDataClass, backupDataClass.googleFileDataClass)
        } else if (updateDirectionType == FileUpdateDirectionType.GOOGLE_TO_LOCAL) {
            updateGoogleToLocal(backupDataClass.googleFileDataClass, backupDataClass.localFileDataClass)
        } else if (updateDirectionType == FileUpdateDirectionType.BOTH) {
            updateLocalToGoogle(backupDataClass.localFileDataClass, backupDataClass.googleFileDataClass)
            updateGoogleToLocal(backupDataClass.googleFileDataClass, backupDataClass.localFileDataClass)
        }


    }

    private fun updateGoogleToLocal(googleFileDataClass: FileDataClass, localFileDataClass: FileDataClass) {

    }

    private fun updateLocalToGoogle(localFileDataClass: FileDataClass, googleFileDataClass: FileDataClass) {
        val missingGoogleFiles = compairFiles(localFileDataClass, googleFileDataClass)

        for(missingFile in missingGoogleFiles){
            if(missingFile.fileStatusType == FileStatusType.GOOGLE_NEWER ||
                missingFile.fileStatusType == FileStatusType.GOOGLE_NEW ||
                missingFile.fileStatusType == FileStatusType.EQUAL)
                continue
            if(missingFile.fileStatusType == FileStatusType.NONE &&
                !missingFile.isFolder
            ){
                println("ERROR - Missing File: ${missingFile.name}")
                continue
            }


            if(missingFile.isFolder && missingFile.fileStatusType == FileStatusType.LOCAL_NEW){
                val googleFolder = createGoogleFolder(missingFile.name, googleFileDataClass.parentId)
                if (googleFolder == null) {
                    println("Error creating Google Folder: ${missingFile.name}")
                    continue
                }
                val newGoogleFileDataClass = convertGoogleFileToFileDataClass(googleFolder)
                googleFileDataClass.fileList.add(newGoogleFileDataClass)
                updateLocalToGoogle(missingFile, newGoogleFileDataClass)
            } else if(missingFile.isFolder && missingFile.fileStatusType == FileStatusType.LOCAL_NEWER){
                // TODO: Upload the folder or just the files in it
            } else if (!missingFile.isFolder && missingFile.fileStatusType == FileStatusType.LOCAL_NEWER){
                val googleFile = googleFileDataClass.fileList.find { it.name == missingFile.name }
                if(googleFile == null){
                    println("Error finding Google File: ${missingFile.name}")
                    continue
                }
                updateGoogleFile(googleFile, missingFile)
                // TODO: Update file
            } else if (!missingFile.isFolder && missingFile.fileStatusType == FileStatusType.LOCAL_NEW){
                // TODO: Upload file
            }


            println("ERROR - Missing File: ${missingFile.name}")
        }

    }


    fun createGoogleFolder(name: String, parentFolder: String): GoogleFile? {
        val googleFile = GoogleFile()
        googleFile.name = name
        googleFile.parents = mutableListOf(parentFolder)
        googleFile.mimeType = "application/vnd.google-apps.folder"

        return googleDriveService.files().create(googleFile).execute()

    }

    fun updateGoogleFile(localDataClass: FileDataClass, googleDataClass: FileDataClass): GoogleFile? {
        return updateGoogleFile(localDataClass, googleDataClass.id)
    }
    fun updateGoogleFile(localDataClass: FileDataClass, googleFileId: String): GoogleFile? {

        val fileContent = FileContent(localDataClass.mimeType,
            File(localDataClass.parentId + "/" + localDataClass.name))
        val googleFile = googleDriveService.files().get(googleFileId).execute()

        return googleDriveService.files().update(googleFile.id, googleFile, fileContent).execute()

    } // TODO: Testing

    fun loadLocalFolderDataInClass(srcLocalFileList: MutableList<File>,
                                    srcFileDataClass: FileDataClass) {
        synchronized(currentActionsLocalLock) {
            currentActionsLocal++
        }
        val thread = Thread {
            for (srcFile in srcLocalFileList) {

                val fileDataClass = convertSrcFileToFileDataClass(srcFile)
                srcFileDataClass.fileList.add(fileDataClass)

                if (fileDataClass.isFolder) {
                    val listFiles = srcFile.listFiles() ?: continue
                    loadLocalFolderDataInClass(listFiles.toMutableList(), fileDataClass)
                }
            }
            synchronized(activeThreadsMapLock){
                activeThreadsMap.remove(Thread.currentThread().id)
            }
            synchronized(currentActionsLocalLock) {
                currentActionsLocal--
            }

        }
        synchronized(threadMapLock) {
            threadMap.put(thread.id, thread)
        }

    }

    fun loadGoogleFolderDataInClass(srcGoogleFileList: MutableList<GoogleFile>,
                                    srcFileDataClass: FileDataClass) {
        synchronized(currentActionsGoogleLock) {
            currentActionsGoogle++
        }
        val thread = Thread {
            for (googleFile in srcGoogleFileList) {

                val fileDataClass = convertGoogleFileToFileDataClass(googleFile)
                srcFileDataClass.fileList.add(fileDataClass)

                if (fileDataClass.isFolder) {

                    val googleDriveFileList = getGoogleDriveFileList(googleFile.id)
                    loadGoogleFolderDataInClass(googleDriveFileList, fileDataClass)
                }
            }

            synchronized(activeThreadsMapLock){
                activeThreadsMap.remove(Thread.currentThread().id)
            }
            synchronized(currentActionsGoogleLock) {
                currentActionsGoogle--
            }

        }
        synchronized(threadMapLock) {
            threadMap.put(thread.id, thread)
        }
    }

    fun getGoogleDriveFileList(parent: String): MutableList<GoogleFile> {
        val googleFileList: MutableList<GoogleFile> = mutableListOf()
        var pageToken: String? = null

        do {
            val execute = googleDriveService
                .files()
                .list()
                .setPageSize(1000)
                .setPageToken(pageToken)
                .setQ("parents='${parent}' and trashed=false")
                .setFields("nextPageToken, files(id, name, mimeType, parents, modifiedTime, createdTime, originalFilename, fileExtension, md5Checksum, parents)")
                .execute()

            googleFileList.addAll(execute.files)
            pageToken = execute.nextPageToken
        } while (pageToken != null)

        return googleFileList
    }

    fun convertGoogleFileToFileDataClass(googleFile: GoogleFile): FileDataClass{


        return FileDataClass(googleFile.name ?: "",
            googleFile.id ?: "",
            if(googleFile.parents.isNullOrEmpty()) "" else googleFile.parents[0],
            googleFile.mimeType ?: "",
            googleFile.modifiedTime.value ?: 0L,
            googleFile.createdTime.value ?: 0L,
            googleFile.originalFilename ?: "",
            googleFile.fileExtension ?: "",
            googleFile.md5Checksum ?: "",
            googleFile.mimeType == "application/vnd.google-apps.folder",
            googleFile.parents ?: mutableListOf()
        )
    }

    fun convertSrcFileToFileDataClass(file: File): FileDataClass{


        return FileDataClass(file.name ?: "",
            "",
            file.parent,
            "",
            file.lastModified() ?: 0L,
             0L,
            file.absolutePath ?: "",
            file.extension ?: "",
            "", // TODO adding later
            file.isDirectory
        )
    }

    fun compairFiles(localFileDataClass: FileDataClass,
                     googleDriveFileDataClass: FileDataClass,
                     directionLocalToGoogle: Boolean = true): MutableList<FileDataClass>{

        if(directionLocalToGoogle) {
            val missingFiles : MutableList<FileDataClass> = mutableListOf()
            for (localFile in localFileDataClass.fileList) {
                if(ignoringFileList.contains(localFile.name)){
                    continue
                }
                val googleFile = googleDriveFileDataClass.fileList.find { it.name == localFile.name }
                if (googleFile == null) {
                    val dataClass = FileDataClass(
                        localFile.name,
                        localFile.id,
                        googleDriveFileDataClass.id,
                        localFile.mimeType,
                        localFile.lastModified,
                        localFile.createdTime,
                        "",
                        localFile.fileExtension,
                        localFile.md5Checksum,
                        localFile.isFolder,
                        localFile.parents,
                        FileStatusType.LOCAL_NEW
                    )
                    localFile.fileStatusType = FileStatusType.LOCAL_NEW
                    missingFiles.add(dataClass)
                } else if (localFile.isFolder) {
                    val compareFiles = compairFiles(localFile, googleFile, directionLocalToGoogle)
                    if (compareFiles.isNotEmpty()) {
                        val copyWithoutList = googleFile.copyWithoutList()
                        copyWithoutList.fileList.addAll(compareFiles)
                        missingFiles.add(copyWithoutList)
                    }
                } else if (isNeedBackup(localFile, googleFile)) {
                    val copyWithoutList = googleFile.copyWithoutList()
                    copyWithoutList.fileList.add(localFile)
                    missingFiles.add(copyWithoutList)
                }
            }
            return missingFiles
        }

        val missingFiles : MutableList<FileDataClass> = mutableListOf()
        for (googleFile in googleDriveFileDataClass.fileList) {
            if(ignoringFileList.contains(googleFile.name)){
                continue
            }
            val localFile = localFileDataClass.fileList.find { it.name == googleFile.name }
            if (localFile == null) {
                val dataClass = FileDataClass(
                    googleFile.name,
                    googleFile.id,
                    googleDriveFileDataClass.parentId,
                    googleFile.mimeType,
                    googleFile.lastModified,
                    googleFile.createdTime,
                    "",
                    googleFile.fileExtension,
                    googleFile.md5Checksum,
                    googleFile.isFolder,
                    googleFile.parents,
                    FileStatusType.GOOGLE_NEW
                )
                googleFile.fileStatusType = FileStatusType.GOOGLE_NEW
                missingFiles.add(dataClass)
            } else if (localFile.isFolder) {
                val compairFiles = compairFiles(googleFile, localFile, directionLocalToGoogle)
                if (compairFiles.isNotEmpty()) {
                    val copyWithoutList = localFile.copyWithoutList()
                    copyWithoutList.fileList.addAll(compairFiles)
                    missingFiles.add(copyWithoutList)
                }
            } else if (isNeedBackup(googleFile, localFile)) {
                val copyWithoutList = localFile.copyWithoutList()
                copyWithoutList.fileList.add(googleFile)
                missingFiles.add(copyWithoutList)
            }
        }

        return missingFiles
    }

    fun getMd5Checksum(file: File): String{
        val md5Checksum = MessageDigest.getInstance("MD5")
        val fileInputStream = FileInputStream(file)
        val buffer = ByteArray(1024)
        var len = fileInputStream.read(buffer)
        while (len != -1) {
            md5Checksum.update(buffer, 0, len)
            len = fileInputStream.read(buffer)
        }
        fileInputStream.close()
        val md5Bytes = md5Checksum.digest()
        val hexString = StringBuffer()
        for (i in md5Bytes.indices) {
            val hex = Integer.toHexString(0xff and md5Bytes[i].toInt())
            if (hex.length == 1) hexString.append('0')
            hexString.append(hex)
        }
        return hexString.toString()


    }

    fun isNeedBackup(driveFile: FileDataClass,
                     localFile: FileDataClass): Boolean{

        return when(backupType){
            FileBackupType.BY_HASH -> {
                driveFile.md5Checksum != localFile.md5Checksum
            } // TODO: To slow to compare by hash
            FileBackupType.BY_MODIFIED_DATE -> {
                return if (driveFile.lastModified < localFile.lastModified) {
                    driveFile.fileStatusType = FileStatusType.GOOGLE_NEWER
                    localFile.fileStatusType = FileStatusType.GOOGLE_NEWER
                    true
                } else if(driveFile.lastModified > localFile.lastModified) {
                    driveFile.fileStatusType = FileStatusType.LOCAL_NEWER
                    localFile.fileStatusType = FileStatusType.LOCAL_NEWER
                    true
                } else {
                    driveFile.fileStatusType = FileStatusType.EQUAL
                    localFile.fileStatusType = FileStatusType.EQUAL
                    false
                }
            }
            FileBackupType.BY_HASH_AND_MODIFIED_DATE -> {
                if (driveFile.lastModified < localFile.lastModified) {

                    driveFile.fileStatusType = FileStatusType.GOOGLE_NEWER
                    localFile.fileStatusType = FileStatusType.GOOGLE_NEWER

                } else if(driveFile.lastModified > localFile.lastModified) {

                    driveFile.fileStatusType = FileStatusType.LOCAL_NEWER
                    localFile.fileStatusType = FileStatusType.LOCAL_NEWER

                } else {

                    driveFile.fileStatusType = FileStatusType.EQUAL
                    localFile.fileStatusType = FileStatusType.EQUAL

                }

                driveFile.md5Checksum != localFile.md5Checksum ||
                        driveFile.lastModified != localFile.lastModified
            } // TODO: To slow to compare by hash

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