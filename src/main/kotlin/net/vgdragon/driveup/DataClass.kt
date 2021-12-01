package net.vgdragon.driveup


class DataClass (var backupFolderMap: HashMap<String, BackUpDataClass> = HashMap())

class BackUpDataClass (var backupFolder: String = "",
                       var backupGoogleFolder: String = "",
                       var lastBackup: Long = 0,
                       var localFileDataClass: FileDataClass = FileDataClass(),
                       var googleFileDataClass: FileDataClass = FileDataClass())


enum class FileStatusType{
    UP_TO_DATE,
    LOCAL_NEWER,
    GOOGLE_NEWER,
    LOCAL_DELETED,
    GOOGLE_DELETED,
    LOCAL_NEW,
    GOOGLE_NEW,
    EQUAL,
    NONE
}

class FileDataClass(var name: String = "",
                    var id: String = "",
                    var parentId: String = "",
                    var mimeType: String = "",
                    var lastModified: Long = 0,
                    var createdTime: Long = 0,
                    var originalFilename: String = "",
                    var fileExtension: String = "",
                    var md5Checksum: String = "",
                    var isFolder: Boolean = true,
                    var parents: MutableList<String> = mutableListOf(),
                    var fileStatusType: FileStatusType = FileStatusType.NONE,
                    var fileList: MutableList<FileDataClass> = mutableListOf()){

    fun copyWithoutList(): FileDataClass {
        return FileDataClass(name,
            id,
            parentId,
            mimeType,
            lastModified,
            createdTime,
            originalFilename,
            fileExtension,
            md5Checksum,
            isFolder,
            parents,
            fileStatusType,
            mutableListOf()
        )
    }
}