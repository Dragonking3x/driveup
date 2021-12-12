# driveup

Driveup is a Kotlin based Google drive synchonisation tool.

Work in process.

**Currently working**

Finding the difference between the local and remote files.

Currently, it only can find the different with "BY_MODIFIED_DATE", 
because getting the Md5 Checksum is slow, if you have a lot of files.


**USE THIS CAREFULLY**

If is still not completely tested, it can be dangerous.

**How to use**

You need to have a Google account, and get an authentication json from
https://console.cloud.google.com/

I will add a guid later.

In Driveup.kt, you see the currently working functions.