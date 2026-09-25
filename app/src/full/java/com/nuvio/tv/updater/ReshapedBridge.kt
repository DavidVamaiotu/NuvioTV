package com.nuvio.tv.updater

import android.content.Context
import com.nuvio.tv.reshaped.ReshapedIdentity
import com.nuvio.tv.updater.model.AppUpdate

internal object ReshapedBridge {
    /**
     * The legacy bridge build offers the latest Nuvio RS APK as an update even though it carries
     * the same version, until Nuvio RS is installed next to it.
     */
    fun offersReshaped(context: Context, update: AppUpdate): Boolean =
        ReshapedIdentity.isLegacyBuild(context) &&
            update.assetName.startsWith(ReshapedApkAssets.PREFIX) &&
            !ReshapedIdentity.isOurInstalledPackage(context, ReshapedIdentity.RESHAPED_PACKAGE)
}
