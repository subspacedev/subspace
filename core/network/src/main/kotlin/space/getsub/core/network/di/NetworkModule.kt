// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.network.di

import android.content.Context
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import space.getsub.core.network.AndroidDeviceInfo
import space.getsub.core.network.AndroidHwidProvider
import space.getsub.core.network.DeviceInfo
import space.getsub.core.network.HwidProvider
import space.getsub.core.network.SubscriptionFetcher
import space.getsub.core.network.SubscriptionSource
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * Qualifies this app's own `versionName`, folded into the default User-Agent by the fetcher and
 * sent as `x-app-version` when a subscription's header follows the device (ARCHITECTURE.md
 * §A.4.1/§A.4.2, M10-B spec §3.5). Provided below via
 * [android.content.pm.PackageManager] rather than `BuildConfig.VERSION_NAME`:
 * `:core:network` cannot see `:app`'s `BuildConfig` (only `:core:data` may
 * depend on `:core:network`, §4 — the reverse edge does not exist either), so
 * a runtime lookup of the app's own installed package is the only value this
 * module can reach on its own. Same pattern as
 * `:feature:settings`'s `AppVersionSource`, which hits the identical
 * `BuildConfig`-is-unreachable problem for the same architectural reason.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
public annotation class AppVersion

@Module
@InstallIn(SingletonComponent::class)
internal abstract class NetworkModule {
    /** Only the Room 8 to 9 migration still reads this, for the legacy value (M10-B spec §3.5). */
    @Binds
    @Singleton
    abstract fun bindHwidProvider(impl: AndroidHwidProvider): HwidProvider

    /** `:core:data` resolves each subscription's device headers from this (M10-B spec §3.5). */
    @Binds
    @Singleton
    abstract fun bindDeviceInfo(impl: AndroidDeviceInfo): DeviceInfo

    /** `:core:data`'s sync pipeline (Task 11) fetches through this seam; [SubscriptionFetcher] is the only impl. */
    @Binds
    @Singleton
    abstract fun bindSubscriptionSource(impl: SubscriptionFetcher): SubscriptionSource

    companion object {
        @Provides
        @AppVersion
        @Singleton
        fun provideAppVersion(
            @ApplicationContext context: Context,
        ): String =
            runCatching {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName
            }.getOrNull().orEmpty()
    }
}
