package net.vgdragon.driveup

import com.google.api.client.http.FileContent
import com.google.api.client.util.DateTime
import com.google.api.services.drive.Drive
import java.io.*
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

    private val googleDriveServiceLock: Object = Object()
    private val tempFileEnding: String = ".olddriveup"

    val maximalRunningPreparingThreads: Int = 5000

    private val preparingThreadMap: MutableMap<Long, Thread> = mutableMapOf()
    private val preparingThreadMapLock: Object = Object()
    private val activePreparingThreadsMap: MutableMap<Long, Thread> = mutableMapOf()
    private val activePreparingThreadsMapLock: Object = Object()

    val maximalRunningCreateFolderThreads: Int = 100

    val maximalRunningUploadThreads: Int = 5

    private val uploadThreadMap: MutableMap<Long, Thread> = mutableMapOf()
    private val uploadThreadMapLock: Object = Object()
    private val activeUploadThreadsMap: MutableMap<Long, Thread> = mutableMapOf()
    private val activeUploadThreadsMapLock: Object = Object()

    val maximalRunningDownloadThreads: Int = 5

    private val downloadThreadMap: MutableMap<Long, Thread> = mutableMapOf()
    private val downloadThreadMapLock: Object = Object()
    private val activeDownloadThreadMap: MutableMap<Long, Thread> = mutableMapOf()
    private val activeDownloadThreadMapLock: Object = Object()

    private var currentActionsGoogle = 0
    private val currentActionsGoogleLock: Object = Object()

    private var currentActionsLocal = 0
    private val currentActionsLocalLock: Object = Object()

    private var currentActionsPreparing = 0
    private val currentActionsPreparingLock: Object = Object()

    val errorList : MutableList<String> = mutableListOf()
    val errorListLock: Object = Object()

    var lastUpdate: Long = 0L

    fun startRoutine(timeBetweenUpdates: Long = 1000, backupTypeForRoutine: FileBackupType = backupType) {
        while (true) {
            Thread.sleep(timeBetweenUpdates)
            //update()
        }
    }

    fun firstPreparation(){
        if(!localBackupFolder.exists()){
            localBackupFolder.mkdirs()
        }
        if(!localBackupFolder.isDirectory){
            println("Local Backup Folder is not a directory")
            return
        }
        lastUpdate = System.currentTimeMillis()
        val localBackupFileList: MutableList<File> = if(localBackupFolder.listFiles().isNullOrEmpty()){
            println("Local Backup Folder is empty")
            mutableListOf()
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


        synchronized(currentActionsLocalLock) {
            currentActionsLocal++
        }
        val thread = Thread {
            loadLocalFolderDataInClass(localBackupFileList, backupDataClass.localFileDataClass)
            synchronized(activePreparingThreadsMapLock) {
                activePreparingThreadsMap.remove(Thread.currentThread().id)
            }
            synchronized(currentActionsLocalLock) {
                currentActionsLocal--
            }
        }
        synchronized(preparingThreadMapLock) {
            preparingThreadMap.put(thread.id, thread)
        }
        loadGoogleFolderDataInClass(googleFileList, backupDataClass.googleFileDataClass)

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
        errorList.forEach {
            println(it)
        }
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
                        printLnWithProperSpacing("Status",
                            "Actions",
                            "Current",
                            0L,
                            "Local $currentActionsLocal Google $currentActionsGoogle (${currentActionsGoogle + currentActionsLocal})")
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
                    if(activeDownloadThreadMap.size < maximalRunningDownloadThreads && downloadThreadMap.isNotEmpty()) {
                        val thread = downloadThreadMap.remove(downloadThreadMap.keys.first())

                        activeDownloadThreadMap[thread!!.id] = thread
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
                    if(activePreparingThreadsMap.size < maximalRunningCreateFolderThreads && preparingThreadMap.isNotEmpty()) {
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

                val missingLocalFiles = comparingFiles(backupDataClass.localFileDataClass, backupDataClass.googleFileDataClass)
                updateLocalToGoogle(missingLocalFiles, backupDataClass.googleFileDataClass)
            }
            FileUpdateDirectionType.GOOGLE_TO_LOCAL -> {
                val missingGoogleFiles = comparingFiles(backupDataClass.localFileDataClass, backupDataClass.googleFileDataClass, false)
                updateGoogleToLocal(backupDataClass.localFileDataClass, missingGoogleFiles)
            }
            FileUpdateDirectionType.BOTH -> {
                val missingLocalFiles = comparingFiles(backupDataClass.localFileDataClass, backupDataClass.googleFileDataClass)
                updateLocalToGoogle(missingLocalFiles, backupDataClass.googleFileDataClass)

                val missingGoogleFiles = comparingFiles(backupDataClass.localFileDataClass, backupDataClass.googleFileDataClass, false)
                updateGoogleToLocal(backupDataClass.localFileDataClass, missingGoogleFiles)
            }
        }


    }

    private fun updateGoogleToLocal(localFileDataClass: FileDataClass, googleFileDataClassList: MutableList<FileDataClass>) {

        for(missingFile in googleFileDataClassList){
            if(missingFile.fileStatusType == FileStatusType.LOCAL_NEWER ||
                missingFile.fileStatusType == FileStatusType.LOCAL_NEW ||
                missingFile.fileStatusType == FileStatusType.EQUAL)
                continue
            if(missingFile.fileStatusType == FileStatusType.NONE &&
                !missingFile.isFolder
            ){


                printLnWithProperSpacing(
                    "ERROR",
                    "UPDATE",
                    "Missing File",
                    0L,
                    missingFile.name
                )
                continue
            }

            if(missingFile.isFolder && missingFile.fileStatusType == FileStatusType.NONE){
                val localFile = localFileDataClass.fileList.find { it.name == missingFile.name }
                if(localFile == null){
                    printLnWithProperSpacing(
                        "ERROR",
                        "UPDATE",
                        "Missing Google File",
                        0L,
                        missingFile.name
                    )
                    continue
                }
                updateGoogleToLocal(localFile, missingFile.fileList)
                continue
            } else if(missingFile.isFolder && missingFile.fileStatusType == FileStatusType.GOOGLE_NEW){
                downloadFolderFromGoogle(localFileDataClass, missingFile)
                continue
            } else if(missingFile.isFolder && missingFile.fileStatusType == FileStatusType.GOOGLE_NEWER){

                printLnWithProperSpacing("Starting",
                    "UPDATE",
                    "Folder",
                    0L,
                    missingFile.name)// TODO create update

                downloadFolderFromGoogle(localFileDataClass, missingFile)
                continue
            } else if (!missingFile.isFolder && missingFile.fileStatusType == FileStatusType.GOOGLE_NEWER){
                updateLocalFile(localFileDataClass, missingFile) // TODO
                continue
            } else if (!missingFile.isFolder && missingFile.fileStatusType == FileStatusType.GOOGLE_NEW){
                downloadFileFromGoogle(localFileDataClass, missingFile)
                continue
            }


            printLnWithProperSpacing(
                "ERROR",
                "UPDATE",
                "Missing File",
                0L,
                missingFile.name
            )
        }
    }
    private fun updateLocalToGoogle(localFileDataClassList: MutableList<FileDataClass>, googleFileDataClass: FileDataClass) {

        for(localFile in localFileDataClassList){
            if(localFile.fileStatusType == FileStatusType.GOOGLE_NEWER ||
                localFile.fileStatusType == FileStatusType.GOOGLE_NEW ||
                localFile.fileStatusType == FileStatusType.EQUAL)
                continue
            if(localFile.fileStatusType == FileStatusType.NONE &&
                !localFile.isFolder
            ){
                printLnWithProperSpacing(
                    "ERROR",
                    "UPDATE",
                    "Missing File",
                    0L,
                    localFile.name
                )
                continue
            }
            if(localFile.isFolder && localFile.fileStatusType == FileStatusType.NONE){
                val googleFile = googleFileDataClass.fileList.find { it.name == localFile.name }
                if(googleFile == null){
                    printLnWithProperSpacing(
                        "ERROR",
                        "UPDATE",
                        "Missing File",
                        0L,
                        localFile.name
                    )
                    continue
                }
                updateLocalToGoogle(localFile.fileList, googleFile)
                continue
            } else if(localFile.isFolder && localFile.fileStatusType == FileStatusType.LOCAL_NEW){

                printLnWithProperSpacing("Starting",
                    "UPLOAD",
                    "Google Folder",
                    0L,
                    localFile.originalFilename + "/" + localFile.name)
                uploadFolderToGoogle(localFile, googleFileDataClass)
                continue
            } else if(localFile.isFolder && localFile.fileStatusType == FileStatusType.LOCAL_NEWER){
                printLnWithProperSpacing("Starting",
                    "UPDATE",
                    "Folder",
                    0L,
                    localFile.name)
                uploadFolderToGoogle(localFile, googleFileDataClass) // TODO create update
                continue
            } else if (!localFile.isFolder && localFile.fileStatusType == FileStatusType.LOCAL_NEWER){
                val googleFile = googleFileDataClass.fileList.find { it.name == localFile.name }
                if(googleFile == null){

                    printLnWithProperSpacing(
                        "Error",
                        "Missing",
                        "Google File",
                        0L,
                        localFile.name
                    )
                    continue
                }
                println("UPDATE - File to Google: ${localFile.name}")
                updateGoogleFile(localFile, googleFile)// TODO delete and upload?
                continue
            } else if (!localFile.isFolder && localFile.fileStatusType == FileStatusType.LOCAL_NEW){
                uploadFileToGoogle(localFile, googleFileDataClass)
                continue
            }


            printLnWithProperSpacing(
                "Error",
                "Missing",
                "File",
                0L,
                localFile.name
            )
        }

    }

    private fun updateLocalFile(localFile: FileDataClass, missingFile: FileDataClass) {
        downloadFileFromGoogle(localFile, missingFile, true)
    }
    fun updateGoogleFile(localFileDataClass: FileDataClass, googleDataClass: FileDataClass): GoogleFile? {

        return updateGoogleFile(localFileDataClass, googleDataClass.id)
    }
    fun updateGoogleFile(localFileDataClass: FileDataClass, googleFileId: String): GoogleFile? {


        val googleFileTemp = googleDriveService.files().get(googleFileId).execute()
        val fileContent = FileContent(googleFileTemp.mimeType,
            File(localFileDataClass.parentId + "/" + localFileDataClass.name))

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


    private fun downloadFolderFromGoogle(localFolderDataClass: FileDataClass, googleFolderDataClass: FileDataClass) {

        synchronized(currentActionsGoogleLock) {
            currentActionsGoogle++
        }
        val thread = Thread {
            var newLocalFileDataClass = localFolderDataClass.fileList.find { it.name == googleFolderDataClass.name }
            if(newLocalFileDataClass == null){
                printLnWithProperSpacing("Action",
                    "CREATING",
                    "Local Folder",
                    0L,
                    googleFolderDataClass.originalFilename + "/" + localFolderDataClass.name
                )

                newLocalFileDataClass = createLocalFolder(localFolderDataClass.originalFilename + "/" + googleFolderDataClass.name, googleFolderDataClass)
            }

            localFolderDataClass.fileList.add(newLocalFileDataClass)
            for (googleFile in googleFolderDataClass.fileList) {
                if (googleFile.isFolder) {
                    downloadFolderFromGoogle(newLocalFileDataClass, googleFile)
                } else {
                    downloadFileFromGoogle(newLocalFileDataClass, googleFile)
                }
            }

            synchronized(activeDownloadThreadMapLock) {
                activeDownloadThreadMap.remove(Thread.currentThread().id)
            }
            synchronized(currentActionsGoogleLock) {
                currentActionsGoogle--
            }

        }
        synchronized(downloadThreadMapLock) {
            downloadThreadMap.put(thread.id, thread)
        }

    }
    private fun downloadFileFromGoogle(localFolderFileDataClass: FileDataClass,
                                       googleFileDataClass: FileDataClass,
                                       update: Boolean = false) {
        synchronized(currentActionsGoogleLock) {
            currentActionsGoogle++
        }
        val thread = Thread {
            val startTime = System.currentTimeMillis()
            val localFile = File(localFolderFileDataClass.originalFilename + "/" + googleFileDataClass.name)
            val tempFile = File(
                localFolderFileDataClass.originalFilename +
                        "/" + googleFileDataClass.name + tempFileEnding
            )
            var error = false

            if(googleFileDataClass.size <= 10L && update) {
                printLnWithProperSpacing("Error",
                    "UPDATE",
                    "File too small",
                    0L,
                    localFolderFileDataClass.originalFilename + "/" + googleFileDataClass.name)
                synchronized(activeDownloadThreadMapLock){
                    activeDownloadThreadMap.remove(Thread.currentThread().id)
                }
                synchronized(currentActionsGoogleLock) {
                    currentActionsGoogle--
                }
                return@Thread

            }
            if (googleFileDataClass.size <= 10L){
                printLnWithProperSpacing("Action",
                    "CREATING",
                    "File",
                    0L,
                    localFolderFileDataClass.originalFilename + "/" + googleFileDataClass.name)
                localFile.createNewFile()
            } else {
                if(update){
                    localFile.renameTo(tempFile)
                }

                var trying = 0
                while (true) {
                    if (update){
                        printLnWithProperSpacing("Starting",
                            "DOWNLOAD",
                            "File update",
                            0L,
                            localFolderFileDataClass.originalFilename + "/" + googleFileDataClass.name)
                    } else {
                        printLnWithProperSpacing("Starting",
                            "DOWNLOAD",
                            "File",
                            0L,
                            localFolderFileDataClass.originalFilename + "/" + googleFileDataClass.name)
                    }


                    try {
                        googleDriveService.files()
                            .get(googleFileDataClass.id)
                            .executeMediaAndDownloadTo(localFile.outputStream()) // TODO Error while downloading java files
                        break
                    } catch (e: Exception) {
                        printLnWithProperSpacing(
                            "Error",
                            "DOWNLOAD",
                            "File",
                            0L,
                            localFolderFileDataClass.originalFilename + "/" + googleFileDataClass.name

                        )
                        val googleDriveController = GoogleDriveController().build()
                        if (googleDriveController != null){
                            googleDriveService = googleDriveController
                        }
                        localFile.delete()
                        trying++
                        if (trying > 3) {
                            synchronized(errorListLock){
                                errorList.add("Error downloading file: " + localFolderFileDataClass.originalFilename + "/" + googleFileDataClass.name)
                            }
                            error = true
                            break
                        }
                        Thread.sleep(1000L * Random().nextInt(1, 5))
                    }

                }

            }

            if(error && update){
                tempFile.renameTo(localFile)
            }
            if(error){
                synchronized(activeDownloadThreadMapLock){
                    activeDownloadThreadMap.remove(Thread.currentThread().id)
                }
                synchronized(currentActionsGoogleLock) {
                    currentActionsGoogle--
                }
                return@Thread
            }

            val timeUntilCompleted = System.currentTimeMillis() - startTime

            if (update) {
                tempFile.delete()
                printLnWithProperSpacing("Finished",
                    "DOWNLOAD",
                    "File update",
                    timeUntilCompleted,
                    localFolderFileDataClass.originalFilename + "/" + googleFileDataClass.name)
            } else {
                printLnWithProperSpacing("Finished",
                    "DOWNLOAD",
                    "File",
                    timeUntilCompleted,
                    localFolderFileDataClass.originalFilename + "/" + googleFileDataClass.name)
            }

            localFile.setLastModified(googleFileDataClass.lastModified)

            if(update){
                localFolderFileDataClass.fileList.find { it.name == localFolderFileDataClass.originalFilename }?.lastModified = googleFileDataClass.lastModified
            } else {
                localFolderFileDataClass.fileList.add(convertSrcFileToFileDataClass(localFile))
            }


            synchronized(activeDownloadThreadMapLock){
                activeDownloadThreadMap.remove(Thread.currentThread().id)
            }
            synchronized(currentActionsGoogleLock) {
                currentActionsGoogle--
            }

        }
        synchronized(downloadThreadMapLock) {
            downloadThreadMap.put(thread.id, thread)
        }
    }
    private fun uploadFolderToGoogle(localFolderDataClass: FileDataClass, googleFolderDataClass: FileDataClass) {

        synchronized(currentActionsLocalLock) {
            currentActionsLocal++
        }
        val thread = Thread {


            var googleFolderList = findGoogleFolder(localFolderDataClass.name, googleFolderDataClass.id)
            val googleFolder = if (googleFolderList.size == 0) {
                printLnWithProperSpacing("Action", "CREATING", "Google Folder", 0L, localFolderDataClass.originalFilename)
                createGoogleFolder(localFolderDataClass, googleFolderDataClass.id)
            } else if (googleFolderList.size == 1) {
                googleFolderList[0]
            } else {
                null
            }
            if (googleFolder == null) {
                printLnWithProperSpacing(
                    "Error",
                    "CREATING",
                    "Google Folder",
                    0L,
                    localFolderDataClass.originalFilename
                )
                return@Thread
            }


            val newGoogleFileDataClass = convertGoogleFileToFileDataClass(googleFolder)
            googleFolderDataClass.fileList.add(newGoogleFileDataClass)
            for (localFile in localFolderDataClass.fileList) {
                if (localFile.isFolder) {
                    uploadFolderToGoogle(localFile, newGoogleFileDataClass)
                } else {
                    uploadFileToGoogle(localFile, newGoogleFileDataClass)
                }
            }

            synchronized(activeUploadThreadsMapLock) {
                activeUploadThreadsMap.remove(Thread.currentThread().id)
            }
            synchronized(currentActionsLocalLock) {
                currentActionsLocal--
            }

        }
        synchronized(uploadThreadMapLock) {
            uploadThreadMap.put(thread.id, thread)
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
            printLnWithProperSpacing("Starting",
                "UPLOAD",
                "File",
                0L,
                localFileDataClass.originalFilename)
            val googleFile = createGoogleFile(localFileDataClass, googleFileDataClass.id)

            if(googleFile != null){
                val newGoogleFileDataClass = convertGoogleFileToFileDataClass(googleFile)

                googleFileDataClass.fileList.add(newGoogleFileDataClass)

                val timeUntilCompleted = System.currentTimeMillis() - startTime
                printLnWithProperSpacing("Finished",
                    "UPLOAD",
                    "File",
                    timeUntilCompleted,
                    localFileDataClass.originalFilename
                )

            } else {
                printLnWithProperSpacing("Error",
                    "UPLOAD",
                    "File",
                    0L,
                    localFileDataClass.originalFilename
                )
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


    fun createGoogleFolder(localFileDataClass: FileDataClass, parentFolder: String): GoogleFile? {
        val googleFile = GoogleFile()
        googleFile.name = localFileDataClass.name
        googleFile.createdTime = DateTime(localFileDataClass.createdTime)
        googleFile.modifiedTime = DateTime(localFileDataClass.lastModified)
        googleFile.parents = mutableListOf(parentFolder)
        googleFile.mimeType = "application/vnd.google-apps.folder"

        return googleDriveService.files().create(googleFile).execute()

    }
    private fun createGoogleFile(localFileDataClass: FileDataClass, parentId: String): com.google.api.services.drive.model.File? {

        val fileContent = FileContent(localFileDataClass.mimeType,
            File(localFileDataClass.parentId + "/" + localFileDataClass.name))
        val fileMetadata = GoogleFile()
        fileMetadata.name = localFileDataClass.name
        fileMetadata.parents = listOf(parentId)
        fileMetadata.createdTime = DateTime(localFileDataClass.createdTime)
        fileMetadata.modifiedTime = DateTime(localFileDataClass.lastModified)

        var trying = 0
        while (true) {
            try {
                val googleFile =
                    googleDriveService.files().create(fileMetadata, fileContent).setFields("id").execute()
                return googleFile
            } catch (e: Exception) {
                printLnWithProperSpacing("Error", "Creating", "Google File", 0L, localFileDataClass.name)
                val googleDriveController = GoogleDriveController().build()
                if (googleDriveController != null){
                    googleDriveService = googleDriveController
                }
                trying++
                if (trying > 3) {
                    synchronized(errorListLock){
                        errorList.add("Error downloading file: " + localFileDataClass.originalFilename + "/" + localFileDataClass.name)
                    }
                    return null
                }
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

        for (srcFile in srcLocalFileList) {

            val fileDataClass = convertSrcFileToFileDataClass(srcFile)
            srcFileDataClass.fileList.add(fileDataClass)

            if (fileDataClass.isFolder) {
                val listFiles = srcFile.listFiles() ?: continue
                loadLocalFolderDataInClass(listFiles.toMutableList(), fileDataClass)
            }
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
                .setFields("nextPageToken, files(id, name, mimeType, parents, modifiedTime, createdTime, originalFilename, fileExtension, md5Checksum, parents, size)")
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
                .setFields("nextPageToken, files(id, name, mimeType, parents, modifiedTime, createdTime, originalFilename, fileExtension, md5Checksum, parents, size)")
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
            googleFile.parents ?: mutableListOf(),
            size = googleFile.getSize() ?: 0L
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
            file.isDirectory,
            size = file.length()
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

    fun printLnWithProperSpacing(starting: String,
                                 function: String,
                                 type: String,
                                 time: Long,
                                 output: String){
        val startingStringSpace = 15
        val functionStringSpace = 15
        val typeStringSpace = 15
        val timeStringSpace = 30

        var startingString = starting
        if(startingString.length < startingStringSpace){
            startingString = startingString.padEnd(typeStringSpace)
        }
        var functionString = function
        if(functionString.length < functionStringSpace){
            functionString = functionString.padEnd(functionStringSpace)
        }
        var typeString = type
        if(typeString.length < typeStringSpace){
            typeString = typeString.padEnd(typeStringSpace)
        }
        var timeString = if(time != 0L){
            val minutes = (time / (1000L * 60L)).toInt()
            val seconds = ((time / 1000L) % 60L).toInt()
            val milliseconds = (time % 1000L).toInt()
            "Completed in: ${minutes}m ${seconds}s ${milliseconds}ms".padEnd(timeStringSpace)
        } else {
            "".padEnd(timeStringSpace)
        }
        println("$startingString $functionString $typeString $timeString: $output")
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