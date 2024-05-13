/*
 * SPDX-FileCopyrightText: 2023 microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.vending.licensing

import android.accounts.Account
import android.accounts.AccountManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.os.RemoteException
import android.util.Log
import com.android.vending.VendingPreferences.isLicensingEnabled
import com.android.volley.RequestQueue
import com.android.volley.toolbox.Volley
import kotlinx.coroutines.runBlocking
import org.microg.gms.auth.AuthConstants
import org.microg.gms.profile.ProfileManager.ensureInitialized
import org.microg.vending.billing.core.HttpClient

class LicensingService : Service() {
    private lateinit var queue: RequestQueue
    private lateinit var accountManager: AccountManager
    private lateinit var androidId: String
    private lateinit var httpClient: HttpClient

    private val mLicenseService: ILicensingService.Stub = object : ILicensingService.Stub() {

        @Throws(RemoteException::class)
        override fun checkLicense(
            nonce: Long,
            packageName: String,
            listener: ILicenseResultListener
        ): Unit = runBlocking {
            Log.v(TAG, "checkLicense($nonce, $packageName)")
            val callingUid = getCallingUid()

            if (!isLicensingEnabled(this@LicensingService)) {
                Log.d(TAG, "not checking license, as it is disabled by user")
                return@runBlocking
            }

            val accounts = accountManager.getAccountsByType(AuthConstants.DEFAULT_ACCOUNT_TYPE)
            val packageManager = packageManager

            lateinit var lastResponse: LicenseResponse
            if (accounts.isEmpty()) {
                handleNoAccounts(packageName, packageManager)
                return@runBlocking
            } else for (account: Account in accounts) {

                lastResponse = httpClient.checkLicense(
                    account,
                    accountManager,
                    androidId,
                    packageName,
                    callingUid,
                    packageManager,
                    V1Request(nonce)
                )

                if (lastResponse.result == LICENSED) {
                    // Do not consider further accounts
                    break
                }
            }

            /* If a license is found, it is now stored in `lastResponse`. Otherwise, it now contains
             * an error. In either case, we should send it to the application.
             */
            try {
                when (lastResponse) {
                    is V1Response -> listener.verifyLicense(lastResponse.result, lastResponse.signedData, lastResponse.signature)
                    is ErrorResponse -> listener.verifyLicense(lastResponse.result, null, null)
                    is V2Response -> Unit // should never happen
                }
            } catch (e: Exception) {
                Log.w(TAG, "Remote threw an exception while returning license result ${lastResponse}")
            }
        }

        @Throws(RemoteException::class)
        override fun checkLicenseV2(
            packageName: String,
            listener: ILicenseV2ResultListener,
            extraParams: Bundle
        ): Unit = runBlocking {
            Log.v(TAG, "checkLicenseV2($packageName, $extraParams)")
            val callingUid = getCallingUid()

            if (!isLicensingEnabled(this@LicensingService)) {
                Log.d(TAG, "not checking license, as it is disabled by user")
                return@runBlocking
            }

            val accounts = accountManager.getAccountsByType(AuthConstants.DEFAULT_ACCOUNT_TYPE)
            val packageManager = packageManager

            if (accounts.isEmpty()) {
                handleNoAccounts(packageName, packageManager)
                return@runBlocking
            } else for (account: Account in accounts) {

                val response = httpClient.checkLicense(
                    account,
                    accountManager,
                    androidId,
                    packageName,
                    callingUid,
                    packageManager,
                    V2Request
                )

                if (response.result == LICENSED && response is V2Response) {
                    val bundle = Bundle()
                    bundle.putString(KEY_V2_RESULT_JWT, response.jwt)

                    try {
                        listener.verifyLicense(response.result, bundle)
                    } catch (e: Exception) {
                        Log.w(TAG, "Remote threw an exception while returning license result ${response}")
                    }
                }
            }

            /*
             * Suppress failures on V2. V2 is commonly used by free apps whose checker
             * will not throw users out of the app if it never receives a response.
             *
             * This means that users who are signed in to a Google account will not
             * get a worse experience in these apps than users that are not signed in.
             *
             * Normally, we would otherwise send the response NOT_LICENSED with an empty
             * bundle here.
             */
            Log.i(TAG, "Suppressed negative license result for package $packageName")

        }

        private fun handleNoAccounts(packageName: String, packageManager: PackageManager) {
            try {
                Log.e(TAG, "not checking license, as user is not signed in")

                packageManager.getPackageInfo(packageName, 0).let {
                    sendLicenseServiceNotification(
                        packageName,
                        packageManager.getApplicationLabel(it.applicationInfo),
                        it.applicationInfo.uid
                    )
                }

            } catch (e: PackageManager.NameNotFoundException) {
                Log.e(TAG, "ignored license request, but package name $packageName was not known!")
                // don't send sign in notification
            }
        }
    }

    override fun onBind(intent: Intent): IBinder {

        ensureInitialized(this)

        contentResolver.query(
            CHECKIN_SETTINGS_PROVIDER,
            arrayOf("androidId"),
            null,
            null,
            null
        ).use { cursor ->
            if (cursor != null) {
                cursor.moveToNext()
                androidId = java.lang.Long.toHexString(cursor.getLong(0))
            }
        }
        queue = Volley.newRequestQueue(this)
        accountManager = AccountManager.get(this)
        httpClient = HttpClient(this)

        return mLicenseService
    }


    companion object {
        private const val TAG = "FakeLicenseService"
        private const val KEY_V2_RESULT_JWT = "LICENSE_DATA"

        private val CHECKIN_SETTINGS_PROVIDER: Uri =
            Uri.parse("content://com.google.android.gms.microg.settings/check-in")
    }
}