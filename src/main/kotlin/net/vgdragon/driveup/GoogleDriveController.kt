package net.vgdragon.driveup

import com.google.api.client.auth.oauth2.Credential
import com.google.api.client.extensions.java6.auth.oauth2.AuthorizationCodeInstalledApp
import com.google.api.client.extensions.jetty.auth.oauth2.LocalServerReceiver
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport
import com.google.api.client.googleapis.services.AbstractGoogleClientRequest
import com.google.api.client.http.HttpRequestInitializer
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.JsonFactory
import com.google.api.client.json.gson.GsonFactory
import com.google.api.client.util.store.FileDataStoreFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveRequestInitializer
import com.google.api.services.drive.DriveScopes
import java.io.*


class GoogleDriveController {
    private val APPLICATION_NAME = "Google Drive API Java Quickstart"

    private val JSON_FACTORY: JsonFactory = GsonFactory.getDefaultInstance()

    // Directory to store user credentials for this application.
    private val CREDENTIALS_FOLDER = File(System.getProperty("user.home"), "credentials")

    private val CLIENT_SECRET_FILE_NAME = "client_secret.json"

    private val SCOPES = listOf(DriveScopes.DRIVE)


    fun build(): Drive? {
        return getDriveService()

    }

    @Throws(IOException::class)
    private fun getCredentials(HTTP_TRANSPORT: NetHttpTransport): Credential? {
        val clientSecretFilePath = File(CREDENTIALS_FOLDER, CLIENT_SECRET_FILE_NAME)
        if (!clientSecretFilePath.exists()) {
            throw FileNotFoundException(
                "Please copy " + CLIENT_SECRET_FILE_NAME //
                        + " to folder: " + CREDENTIALS_FOLDER.absolutePath
            )
        }

        // Load client secrets.
        val `in`: InputStream = FileInputStream(clientSecretFilePath)
        val clientSecrets = GoogleClientSecrets.load(JSON_FACTORY, InputStreamReader(`in`))

        // Build flow and trigger user authorization request.
        val flow = GoogleAuthorizationCodeFlow.Builder(
            HTTP_TRANSPORT, JSON_FACTORY,
            clientSecrets, SCOPES
        ).setDataStoreFactory(FileDataStoreFactory(CREDENTIALS_FOLDER))
            .setAccessType("offline").build()
        return AuthorizationCodeInstalledApp(flow, LocalServerReceiver()).authorize("user")
    }

    private fun getDriveService(): Drive? {
        println("CREDENTIALS_FOLDER: " + CREDENTIALS_FOLDER.absolutePath)

        // 1: Create CREDENTIALS_FOLDER
        if (!CREDENTIALS_FOLDER.exists()) {
            CREDENTIALS_FOLDER.mkdirs()
            println("Created Folder: " + CREDENTIALS_FOLDER.absolutePath)
            println("Copy file $CLIENT_SECRET_FILE_NAME into folder above.. and rerun this class!!")
           return null
        }

        // 2: Build a new authorized API client service.
        val HTTP_TRANSPORT = GoogleNetHttpTransport.newTrustedTransport()

        HTTP_TRANSPORT.createRequestFactory()
        // 3: Read client_secret.json file & create Credential object.
        val credential = getCredentials(HTTP_TRANSPORT)


        // 5: Create Google Drive Service.
        val build = Drive.Builder(HTTP_TRANSPORT, JSON_FACTORY, credential)
            .setApplicationName(APPLICATION_NAME)
            .setDriveRequestInitializer(DriveRequestInitializer())
            .build()

        return build
    }


}


