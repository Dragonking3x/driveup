package net.vgdragon.driveup

import com.google.api.client.http.FileContent
import com.google.api.client.util.DateTime
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
                  var googleDriveService: Drive,
                  val localBackupFolder: File,
                  val googleDriveFolder: String = "",
                  val ignoringFileList: MutableList<String> = mutableListOf(),
                  val backupType: FileBackupType = FileBackupType.BY_MODIFIED_DATE,
                  val updateDirectionType: FileUpdateDirectionType = FileUpdateDirectionType.LOCAL_TO_GOOGLE) {

    private val tempFileEnding: String = ".olddriveup"

    val maximalRunningPreparingThreads: Int = 5000

    private val preparingThreadMap: MutableMap<Long, Thread> = mutableMapOf()
    private val preparingThreadMapLock: Object = Object()
    private val activePreparingThreadsMap: MutableMap<Long, Thread> = mutableMapOf()
    private val activePreparingThreadsMapLock: Object = Object()

    val maximalRunningUploadThreads: Int = 5

    private val uploadThreadMap: MutableMap<Long, Thread> = mutableMapOf()
    private val uploadThreadMapLock: Object = Object()
    private val activeUploadThreadsMap: MutableMap<Long, Thread> = mutableMapOf()
    private val activeUploadThreadsMapLock: Object = Object()

    val maximalRunningDownloadThreads: Int = 5

    private val downloadThreadMap: MutableMap<Long, Thread> = mutableMapOf()
    private val downloadThreadMapLock: Object = Object()
    private val activeDownloadThreadMapMap: MutableMap<Long, Thread> = mutableMapOf()
    private val activeDownloadThreadMapLock: Object = Object()

    private var currentActionsGoogle = 0
    private val currentActionsGoogleLock: Object = Object()

    private var currentActionsLocal = 0
    private val currentActionsLocalLock: Object = Object()

    private var currentActionsPreparing = 0
    private val currentActionsPreparingLock: Object = Object()

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
        threadListRegulationPreperation()
        val timeUntilCompleted = System.currentTimeMillis() - startTime

        println("----- Finished Loading File Data  -----")
        val minutes = (timeUntilCompleted / (1000L * 60L)).toInt()
        val seconds = ((timeUntilCompleted / 1000L) % 60L).toInt()
        val milliseconds = (timeUntilCompleted % 1000L).toInt()
        println("Finished in: $minutes Minutes " +
                "${seconds}.${milliseconds} Seconds")
        //saveDataClass(dataClass)
        println("----- Update Files  -----")

        updateFiles(backupDataClass) // TODO
        threadListRegulationDownloadAndUpload()

        println("----- Finished Files  -----")
        println()
    }

    fun threadListRegulationPreperation(){
        var finished = false

        var lastLineOutput = System.currentTimeMillis()
        while (!finished) {

            synchronized(preparingThreadMapLock) {
                synchronized(activePreparingThreadsMapLock) {
                    if(activePreparingThreadsMap.size < maximalRunningPreparingThreads && preparingThreadMap.isNotEmpty()) {
                        val thread = preparingThreadMap.remove(preparingThreadMap.keys.first())

                        activePreparingThreadsMap[thread!!.id] = thread
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
                    synchronized(currentActionsLocalLock) {
                        println("Current Actions: Local $currentActionsLocal Google $currentActionsGoogle (${currentActionsGoogle + currentActionsLocal})")
                    }
                }
                lastLineOutput = System.currentTimeMillis()
            }
            Thread.sleep(10)
        }
    }
    fun threadListRegulationDownloadAndUpload(){
        var finished = false

        var lastLineOutput = System.currentTimeMillis()
        while (!finished) {
            synchronized(downloadThreadMapLock) {
                synchronized(activeDownloadThreadMapLock) {
                    if(activeDownloadThreadMapMap.size < maximalRunningDownloadThreads && downloadThreadMap.isNotEmpty()) {
                        val thread = downloadThreadMap.remove(downloadThreadMap.keys.first())

                        activeDownloadThreadMapMap[thread!!.id] = thread
                        thread.start()
                    }
                }
            }
            synchronized(uploadThreadMapLock) {
                synchronized(activeUploadThreadsMapLock) {
                    if(activeUploadThreadsMap.size < maximalRunningUploadThreads && uploadThreadMap.isNotEmpty()) {
                        val thread = uploadThreadMap.remove(uploadThreadMap.keys.first())

                        activeUploadThreadsMap[thread!!.id] = thread
                        thread.start()
                    }
                }
            }
            synchronized(preparingThreadMapLock) {
                synchronized(activePreparingThreadsMapLock) {
                    if(activePreparingThreadsMap.size < maximalRunningPreparingThreads && preparingThreadMap.isNotEmpty()) {
                        val thread = preparingThreadMap.remove(preparingThreadMap.keys.first())

                        activePreparingThreadsMap[thread!!.id] = thread
                        thread.start()
                    }
                }
            }
            synchronized(currentActionsGoogleLock) {
                synchronized(currentActionsLocalLock) {
                    synchronized(currentActionsPreparingLock) {
                        if (currentActionsGoogle <= 0 && currentActionsLocal <= 0 && currentActionsPreparing <= 0) {
                            finished = true
                        }
                    }
                }
            }
            /*if( System.currentTimeMillis() - lastLineOutput > 1000){
                synchronized(currentActionsGoogleLock) {
                    synchronized(currentActionsLocalLock) {
                        synchronized(currentActionsPreparingLock) {

                            println(
                                "Current Actions: " +
                                        "Local $currentActionsLocal " +
                                        "Google $currentActionsGoogle " +
                                        "Preparing $currentActionsPreparing " +
                                        "= Sum ${currentActionsGoogle + currentActionsLocal}"
                            )
                        }
                    }
                lastLineOutput = System.currentTimeMillis()
            }}*/
            Thread.sleep(100)
        }
    }

    fun updateFiles(backupDataClass: BackUpDataClass){
        when (updateDirectionType) {
            FileUpdateDirectionType.LOCAL_TO_GOOGLE -> {
                updateLocalToGoogle(backupDataClass.localFileDataClass, backupDataClass.googleFileDataClass)
            }
            FileUpdateDirectionType.GOOGLE_TO_LOCAL -> {
                updateGoogleToLocal(backupDataClass.googleFileDataClass, backupDataClass.localFileDataClass)
            }
            FileUpdateDirectionType.BOTH -> {
                updateLocalToGoogle(backupDataClass.localFileDataClass, backupDataClass.googleFileDataClass)
                updateGoogleToLocal(backupDataClass.googleFileDataClass, backupDataClass.localFileDataClass)
            }
        }


    }

    private fun updateGoogleToLocal(googleFileDataClass: FileDataClass, localFileDataClass: FileDataClass) {
        val missingLocalFiles = comparingFiles(localFileDataClass, googleFileDataClass, false)

        for(missingFile in missingLocalFiles){
            if(missingFile.fileStatusType == FileStatusType.LOCAL_NEWER ||
                missingFile.fileStatusType == FileStatusType.LOCAL_NEW ||
                missingFile.fileStatusType == FileStatusType.EQUAL)
                continue
            if(missingFile.fileStatusType == FileStatusType.NONE &&
                !missingFile.isFolder
            ){
                println("ERROR - Missing File: ${missingFile.name}")
                continue
            }


            if((missingFile.isFolder && missingFile.fileStatusType == FileStatusType.GOOGLE_NEW) ||
                (missingFile.isFolder && missingFile.fileStatusType == FileStatusType.NONE)){
                downloadFolderFromGoogle(missingFile, localFileDataClass)
                continue
            } else if(missingFile.isFolder && missingFile.fileStatusType == FileStatusType.GOOGLE_NEWER){
                println("UPDATE - Folder: ${missingFile.name}")// TODO create update
                downloadFolderFromGoogle(missingFile, localFileDataClass)
                continue
            } else if (!missingFile.isFolder && missingFile.fileStatusType == FileStatusType.GOOGLE_NEWER){
                val localFile = googleFileDataClass.fileList.find { it.name == missingFile.name }
                if(localFile == null){
                    println("Error finding Google File: ${missingFile.name}")
                    continue
                }
                updateLocalFile(missingFile, localFile) // TODO
                continue
            } else if (!missingFile.isFolder && missingFile.fileStatusType == FileStatusType.GOOGLE_NEW){
                downloadFileFromGoogle(missingFile, localFileDataClass)
                continue
            }


            println("ERROR - Missing File: ${missingFile.name}")
        }
    }
    private fun updateLocalToGoogle(localFileDataClass: FileDataClass, googleFileDataClass: FileDataClass) {
        val missingGoogleFiles = comparingFiles(localFileDataClass, googleFileDataClass)

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


            if((missingFile.isFolder && missingFile.fileStatusType == FileStatusType.LOCAL_NEW) ||
                (missingFile.isFolder && missingFile.fileStatusType == FileStatusType.NONE)){

                println("UPLOAD - Google Folder: ${missingFile.name}")
                uploadFolderToGoogle(missingFile, googleFileDataClass)
                continue
            } else if(missingFile.isFolder && missingFile.fileStatusType == FileStatusType.LOCAL_NEWER){
                println("UPDATE - Folder: ${missingFile.name}")
                uploadFolderToGoogle(missingFile, googleFileDataClass) // TODO create update
                continue
            } else if (!missingFile.isFolder && missingFile.fileStatusType == FileStatusType.LOCAL_NEWER){
                continue
                val googleFile = googleFileDataClass.fileList.find { it.name == missingFile.name }
                if(googleFile == null){
                    println("Error finding Google File: ${missingFile.name}")
                    continue
                }
                println("UPDATE - File to Google: ${missingFile.name}")
                updateGoogleFile(missingFile, googleFile)// TODO delete and upload?

            } else if (!missingFile.isFolder && missingFile.fileStatusType == FileStatusType.LOCAL_NEW){
                uploadFileToGoogle(missingFile, googleFileDataClass)
                continue
            }


            println("ERROR - Missing File: ${missingFile.name}")
        }

    }

    private fun updateLocalFile(missingFile: FileDataClass, localFile: FileDataClass) {
        downloadFileFromGoogle(missingFile, localFile, true)
    }
    fun updateGoogleFile(localFileDataClass: FileDataClass, googleDataClass: FileDataClass): GoogleFile? {

        return updateGoogleFile(localFileDataClass, googleDataClass.id)
    }
    fun updateGoogleFile(localFileDataClass: FileDataClass, googleFileId: String): GoogleFile? {

        val fileContent = FileContent(localFileDataClass.mimeType,
            File(localFileDataClass.parentId + "/" + localFileDataClass.name))
        val googleFileTemp = googleDriveService.files().get(googleFileId).execute()
        val googleFile = GoogleFile()
        googleFile.name = localFileDataClass.name
        googleFile.parents = googleFileTemp.parents
        googleFile.createdTime = DateTime(localFileDataClass.createdTime)
        googleFile.modifiedTime = DateTime(localFileDataClass.lastModified)

        return googleDriveService.files().update(googleFileTemp.id, googleFile, fileContent).execute()

    } // TODO: Testing
    fun updateGoogleFile(localDataClass: FileDataClass, googleFile: GoogleFile): GoogleFile? {
        val fileContent = FileContent(localDataClass.mimeType,
            File(localDataClass.parentId + "/" + localDataClass.name))
        return googleDriveService.files().update(googleFile.id, googleFile, fileContent).execute()
    } // TODO: Testing


    private fun downloadFolderFromGoogle(missingFile: FileDataClass, localFileDataClass: FileDataClass) {

        synchronized(currentActionsPreparingLock) {
            currentActionsPreparing++
        }
        val thread = Thread {

            val newLocalFileDataClass = if (localFileDataClass.fileStatusType == FileStatusType.NONE) {
                localFileDataClass
            } else {
                println("CREATING - Google Folder: ${localFileDataClass.originalFilename + missingFile.name}")
                createLocalFolder(localFileDataClass.originalFilename, missingFile)
            }
            localFileDataClass.fileList.add(newLocalFileDataClass)
            for (file in missingFile.fileList) {
                if (file.isFolder) {
                    downloadFolderFromGoogle(file, newLocalFileDataClass)
                } else {
                    downloadFileFromGoogle(file, newLocalFileDataClass)
                }
            }

            synchronized(activePreparingThreadsMapLock) {
                activePreparingThreadsMap.remove(Thread.currentThread().id)
            }
            synchronized(currentActionsPreparingLock) {
                currentActionsPreparing--
            }

        }
        synchronized(preparingThreadMapLock) {
            preparingThreadMap.put(thread.id, thread)
        }

    }
    private fun downloadFileFromGoogle(missingFile: FileDataClass, localFileDataClass: FileDataClass, update: Boolean = false) {
        synchronized(currentActionsGoogleLock) {
            currentActionsGoogle++
        }
        val thread = Thread {
            val startTime = System.currentTimeMillis()

            if(update){
                println("Start - DOWNLOAD File (update): ${localFileDataClass.name}")
                val file = File(localFileDataClass.originalFilename + "/" + missingFile.name)
                val tempFile = File(
                    localFileDataClass.originalFilename +
                            "/" + missingFile.name + tempFileEnding
                )
                file.renameTo(tempFile)
                tempFile.delete()

                val timeUntilCompleted = System.currentTimeMillis() - startTime
                val minutes = (timeUntilCompleted / (1000L * 60L)).toInt()
                val seconds = ((timeUntilCompleted / 1000L) % 60L).toInt()
                val milliseconds = (timeUntilCompleted % 1000L).toInt()
                println("Finished - DOWNLOAD File (update) $minutes Minutes " +
                        "${seconds}.${milliseconds} Seconds: ${localFileDataClass.name}")
            } else {
                println("Start - DOWNLOAD File: ${localFileDataClass.originalFilename + "/" + missingFile.name}")
                googleDriveService.files()
                    .get(missingFile.id)
                    .executeAndDownloadTo(
                        File(localFileDataClass.originalFilename + "/" + missingFile.name)
                            .outputStream()
                    )

                val timeUntilCompleted = System.currentTimeMillis() - startTime
                val minutes = (timeUntilCompleted / (1000L * 60L)).toInt()
                val seconds = ((timeUntilCompleted / 1000L) % 60L).toInt()
                val milliseconds = (timeUntilCompleted % 1000L).toInt()
                println("Finished - DOWNLOAD File $minutes Minutes " +
                        "${seconds}.${milliseconds} Seconds: ${localFileDataClass.originalFilename + "/" + missingFile.name}")
            }


            synchronized(activePreparingThreadsMapLock){
                activePreparingThreadsMap.remove(Thread.currentThread().id)
            }
            synchronized(currentActionsGoogleLock) {
                currentActionsGoogle--
            }

        }
        synchronized(preparingThreadMapLock) {
            preparingThreadMap.put(thread.id, thread)
        }
    }
    private fun uploadFolderToGoogle(missingFile: FileDataClass, parentGoogleFile: FileDataClass) {

        synchronized(currentActionsPreparingLock) {
            currentActionsPreparing++
        }
        val thread = Thread {
            println("CREATING - Google Folder: ${missingFile.originalFilename}")


            var googleFolderList = findGoogleFolder(missingFile.name, parentGoogleFile.id)
            val googleFolder = if (googleFolderList.size == 0) {
                createGoogleFolder(missingFile, parentGoogleFile.id)
            } else if (googleFolderList.size == 1) {
                googleFolderList[0]
            } else {
                null
            }
            if (googleFolder == null) {
                println("Error finding Google Folder: ${missingFile.name}")
                return@Thread
            }


            val newGoogleFileDataClass = convertGoogleFileToFileDataClass(googleFolder)
            parentGoogleFile.fileList.add(newGoogleFileDataClass)
            for (file in missingFile.fileList) {
                if (file.isFolder) {
                    println("CREATING - Google Folder: ${missingFile.originalFilename}")
                    uploadFolderToGoogle(file, newGoogleFileDataClass)
                } else {
                    uploadFileToGoogle(file, newGoogleFileDataClass)
                }
            }

            synchronized(activePreparingThreadsMapLock) {
                activePreparingThreadsMap.remove(Thread.currentThread().id)
            }
            synchronized(currentActionsPreparingLock) {
                currentActionsPreparing--
            }

        }
        synchronized(preparingThreadMapLock) {
            preparingThreadMap.put(thread.id, thread)
        }
    }

    private fun createLocalFolder(filePath: String, googleFileDataClass: FileDataClass): FileDataClass {
        val file = File(filePath)
        if (!file.mkdir()) {
            throw Exception("Error creating Local Folder: ${filePath}")
        }
        file.setLastModified(googleFileDataClass.lastModified)
        return convertSrcFileToFileDataClass(file)
    }
    fun uploadFileToGoogle(localFileDataClass: FileDataClass, googleFileDataClass: FileDataClass){
        synchronized(currentActionsLocalLock) {
            currentActionsLocal++
        }
        val thread = Thread {
            val startTime = System.currentTimeMillis()

            println("Start - UPLOAD File: ${localFileDataClass.name}")
            val googleFile = createGoogleFile(localFileDataClass, googleFileDataClass.id)
            if(googleFile != null){
                val newGoogleFileDataClass = convertGoogleFileToFileDataClass(googleFile)

                googleFileDataClass.fileList.add(newGoogleFileDataClass)

            } else {
                println("Error creating Google File: ${localFileDataClass.name}")
            }

            val timeUntilCompleted = System.currentTimeMillis() - startTime
            val minutes = (timeUntilCompleted / (1000L * 60L)).toInt()
            val seconds = ((timeUntilCompleted / 1000L) % 60L).toInt()
            val milliseconds = (timeUntilCompleted % 1000L).toInt()
            println("Finished - UPLOAD File:$minutes Minutes " +
                    "${seconds}.${milliseconds} Seconds: ${localFileDataClass.name}")

            synchronized(activePreparingThreadsMapLock){
                activePreparingThreadsMap.remove(Thread.currentThread().id)
            }
            synchronized(currentActionsLocalLock) {
                currentActionsLocal--
            }

        }
        synchronized(preparingThreadMapLock) {
            preparingThreadMap.put(thread.id, thread)
        }
    }


    fun createGoogleFolder(localFileDataClass: FileDataClass, parentFolder: String): GoogleFile? {
        val googleFile = GoogleFile()
        googleFile.name = localFileDataClass.name
        googleFile.createdTime = DateTime(localFileDataClass.createdTime)
        googleFile.modifiedTime = DateTime(localFileDataClass.lastModified)
        googleFile.parents = mutableListOf(parentFolder)
        googleFile.mimeType = "application/vnd.google-apps.folder"

        return googleDriveService.files().create(googleFile).execute()

    }
    private fun createGoogleFile(localFileDataClass: FileDataClass, parentId: String): GoogleFile {

        val fileContent = FileContent(localFileDataClass.mimeType,
            File(localFileDataClass.parentId + "/" + localFileDataClass.name))
        val fileMetadata = GoogleFile()
        fileMetadata.name = localFileDataClass.name
        fileMetadata.parents = listOf(parentId)
        fileMetadata.createdTime = DateTime(localFileDataClass.createdTime)
        fileMetadata.modifiedTime = DateTime(localFileDataClass.lastModified)


        while (true) {
            try {
                val googleFile = googleDriveService.files().create(fileMetadata, fileContent).setFields("id").execute()
                return googleFile
            } catch (e: Exception) {
                println("Error creating Google File: ${localFileDataClass.name}")

                Thread.sleep(10000)
            } //TODO Find solution to an upload error
        }
    }


    fun isNeedBackup(driveFile: FileDataClass,
                     localFile: FileDataClass): Boolean{

        return when(backupType){
            FileBackupType.BY_HASH -> {
                driveFile.md5Checksum != localFile.md5Checksum
            } // TODO: To slow to compare by hash
            FileBackupType.BY_MODIFIED_DATE -> {
                return if (driveFile.lastModified < localFile.lastModified) {
                    if (!driveFile.isFolder) {
                        driveFile.fileStatusType = FileStatusType.GOOGLE_NEWER
                        localFile.fileStatusType = FileStatusType.GOOGLE_NEWER
                    }
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
            synchronized(activePreparingThreadsMapLock){
                activePreparingThreadsMap.remove(Thread.currentThread().id)
            }
            synchronized(currentActionsLocalLock) {
                currentActionsLocal--
            }

        }
        synchronized(preparingThreadMapLock) {
            preparingThreadMap.put(thread.id, thread)
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

            synchronized(activePreparingThreadsMapLock){
                activePreparingThreadsMap.remove(Thread.currentThread().id)
            }
            synchronized(currentActionsGoogleLock) {
                currentActionsGoogle--
            }

        }
        synchronized(preparingThreadMapLock) {
            preparingThreadMap.put(thread.id, thread)
        }
    }

    fun findGoogleFolder(name: String, parentId: String): MutableList<GoogleFile> {
        val googleFileList: MutableList<GoogleFile> = mutableListOf()
        var pageToken: String? = null

        do {
            val execute = googleDriveService
                .files()
                .list()
                .setPageSize(1000)
                .setPageToken(pageToken)
                .setQ("parents='${parentId}' and trashed=false and name='${name}'")
                .setFields("nextPageToken, files(id, name, mimeType, parents, modifiedTime, createdTime, originalFilename, fileExtension, md5Checksum, parents)")
                .execute()

            googleFileList.addAll(execute.files)
            pageToken = execute.nextPageToken
        } while (pageToken != null)
        return googleFileList
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
            if(googleFile.modifiedTime == null){
                0L
            } else {
                googleFile.modifiedTime.value ?: 0L
            },
            if(googleFile.createdTime == null){
                0L
            } else {
                googleFile.createdTime.value ?: 0L
            },
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

    fun comparingFiles(localFileDataClass: FileDataClass,
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
                    localFile.fileStatusType = FileStatusType.LOCAL_NEW
                    missingFiles.add(localFile)
                } else if (localFile.isFolder) {
                    val compareFiles = comparingFiles(localFile, googleFile, directionLocalToGoogle)
                    if (compareFiles.isNotEmpty()) {
                        val copyWithoutList = localFile.copyWithoutList()
                        copyWithoutList.fileList.addAll(compareFiles)
                        missingFiles.add(copyWithoutList)
                    }
                } else if (isNeedBackup(localFile, googleFile)) {
                    val copyWithoutList = localFile.copyWithoutList()
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

                googleFile.fileStatusType = FileStatusType.GOOGLE_NEW
                missingFiles.add(googleFile)
            } else if (localFile.isFolder) {
                val compairFiles = comparingFiles(googleFile, localFile, directionLocalToGoogle)
                if (compairFiles.isNotEmpty()) {
                    val copyWithoutList = googleFile.copyWithoutList()
                    copyWithoutList.fileList.addAll(compairFiles)
                    missingFiles.add(copyWithoutList)
                }
            } else if (isNeedBackup(googleFile, localFile)) {
                val copyWithoutList = googleFile.copyWithoutList()
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


    fun sleepUntil(date: Date){
        sleepUntil(date.time)
    }
    private fun sleepUntil(time: Long){
        while (System.currentTimeMillis() < time) {
            Thread.sleep(1000)
        }
    }

}