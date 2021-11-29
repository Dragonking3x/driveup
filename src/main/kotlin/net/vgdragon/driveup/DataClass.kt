package net.vgdragon.driveup

class DataClass (var backupFolderMap: HashMap<String, BackUpDataClass> = HashMap())

class BackUpDataClass (var backupFolder: String = "",
                       var backupGoogleFolder: String = "",
                       var lastBackup: Long = 0){


}
