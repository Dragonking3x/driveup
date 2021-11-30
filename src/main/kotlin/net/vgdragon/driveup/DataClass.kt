package net.vgdragon.driveup

import com.google.api.services.drive.model.File as GoogleFile
import java.io.File

class DataClass (var backupFolderMap: HashMap<String, BackUpDataClass> = HashMap())

class BackUpDataClass (var backupFolder: String = "",
                       var backupGoogleFolder: String = "",
                       var lastBackup: Long = 0,
                       var localFileDataClass: FileDataClass = FileDataClass(),
                       var googleFileDataClass: FileDataClass = FileDataClass())




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
                    var fileList: MutableList<FileDataClass> = mutableListOf())