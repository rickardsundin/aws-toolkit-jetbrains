// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package software.aws.toolkits.gradle.intellij

import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.provider.ProviderFactory

abstract class ToolkitIntelliJExtension(private val providers: ProviderFactory) {
    abstract val ideFlavor: Property<IdeFlavor>

    fun ideProfile() = IdeVersions.ideProfile(providers)

    fun version(): Provider<String?> = productProfile().flatMap { profile ->
        providers.provider { profile.version() }
    }

    fun localPath(): Provider<String?> = productProfile().flatMap { profile ->
        providers.provider { profile.localPath() }
    }

    fun productProfile(): Provider<out ProductProfile> = ideFlavor.flatMap { flavor ->
        when (flavor) {
            IdeFlavor.IC -> ideProfile().map { it.community }
            IdeFlavor.IU -> ideProfile().map { it.ultimate }
            IdeFlavor.RD -> ideProfile().map { it.rider }
            IdeFlavor.GW -> ideProfile().map { it.gateway!! }
        }
    }
}
