package net.vgdragon.driveup

import com.google.api.services.drive.Drive
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.*
import com.google.api.services.drive.model.File as GoogleFile


enum class FileBackupType {
    BY_MODIFIED_DATE,
    BY_HASH,
    BY_NAME_AND_HASH,
    BY_NAME_AND_MODIFIED_DATE,
    BY_NAME_AND_HASH_AND_MODIFIED_DATE
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
                  val localBackupFolder: File,
                  val googleDriveFolder: String = "",
                  val backupType: FileBackupType = FileBackupType.BY_MODIFIED_DATE){

    private val threadMap: MutableMap<Long, Thread> = mutableMapOf()
    private val threadMapLock: Object = Object()
    private val activeThreadsMap: MutableMap<Long, Thread> = mutableMapOf()
    private val activeThreadsMapLock: Object = Object()

    val maximalRunningThreads: Int = 8
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

        val rootFileDataClass = FileDataClass(
            "",
            "",
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
        backupDataClass.googleFileDataClass = rootFileDataClass

        println("----- Start Loading Google Drive Files Data -----")
        println("Start Date: " + Date())

        val startTime = System.currentTimeMillis()
        val googleFileList: MutableList<GoogleFile> = getGoogleDriveFileList(googleDriveFolder)

        println("----- Finished loading root folder-----")
        println("----- Start Loading File Data -----")

        loadGoogleFolderDataInClass(googleFileList, rootFileDataClass)
        loadLocalFolderDataInClass(localBackupFileList, rootFileDataClass)
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

                    if (currentActionsGoogle <= 0)
                        finished = true
                }
            }
            if( System.currentTimeMillis() - lastLineOutput > 1000){
                synchronized(currentActionsGoogleLock) {
                    println("Current Actions: $currentActionsGoogle")
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
        saveDataClass(dataClass)
    println("----- Start to Compare Files  -----")

    val missingFiles = compairFiles(backupDataClass.localFileDataClass, backupDataClass.googleFileDataClass)

    println("----- Finished to Compare Files  -----")
    for (file in missingFiles) {
        println("Missing File: " + file.name)
    }

    println()

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


/*

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
*/

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
            if (file.isDirectory) "" else getMd5Checksum(file), //TODO  too slow
            file.isDirectory
        )
    }

    fun compairFiles(localFileDataClass: FileDataClass,
                     googleDriveFileDataClass: FileDataClass,
                     directionLocalToGoogle: Boolean = true): MutableList<FileDataClass>{

        if(directionLocalToGoogle) {
            val missingFiles : MutableList<FileDataClass> = mutableListOf()
            for (localFile in localFileDataClass.fileList) {
                val googleFile = googleDriveFileDataClass.fileList.find { it.name == localFile.name }
                if (googleFile == null) {
                    val dataClass = FileDataClass(
                        localFile.name,
                        localFile.id,
                        googleDriveFileDataClass.parentId,
                        localFile.mimeType,
                        localFile.lastModified,
                        localFile.createdTime,
                        "",
                        localFile.fileExtension,
                        localFile.md5Checksum,
                        localFile.isFolder,
                        localFile.parents
                    )
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
                    googleFile.parents
                )
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

        when(backupType){
            FileBackupType.BY_HASH -> {
                return driveFile.md5Checksum != localFile.md5Checksum
            }
            FileBackupType.BY_MODIFIED_DATE -> {
                return driveFile.lastModified != localFile.lastModified
            }
            FileBackupType.BY_NAME_AND_MODIFIED_DATE -> {
                return driveFile.name != localFile.name ||
                        driveFile.lastModified != localFile.lastModified
            }
            FileBackupType.BY_NAME_AND_HASH_AND_MODIFIED_DATE -> {
                return driveFile.name != localFile.name ||
                        driveFile.md5Checksum != localFile.md5Checksum ||
                        driveFile.lastModified != localFile.lastModified
            }
            FileBackupType.BY_NAME_AND_HASH -> {
                return driveFile.name != localFile.name ||
                        driveFile.md5Checksum != localFile.md5Checksum
            }

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