package com.opendash.app.assistant.provider

import com.opendash.app.data.preferences.AppPreferences
import com.opendash.app.data.preferences.PreferenceKeys
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

sealed interface RemoteDataDecision {
    companion object {
        fun decide(isLocal: Boolean, localOnly: Boolean, disclosureAccepted: Boolean): RemoteDataDecision =
            RemoteDataDecisionPolicy.decide(isLocal, localOnly, disclosureAccepted)
    }

    data object Allow : RemoteDataDecision
    data object RequiresDisclosure : RemoteDataDecision
    data object BlockedByLocalOnly : RemoteDataDecision
}

object RemoteDataDecisionPolicy {
    fun decide(isLocal: Boolean, localOnly: Boolean, disclosureAccepted: Boolean): RemoteDataDecision = when {
        isLocal -> RemoteDataDecision.Allow
        localOnly -> RemoteDataDecision.BlockedByLocalOnly
        disclosureAccepted -> RemoteDataDecision.Allow
        else -> RemoteDataDecision.RequiresDisclosure
    }
}

@Singleton
class RemoteDataPolicy @Inject constructor(
    private val preferences: AppPreferences
) {
    suspend fun decide(provider: AssistantProvider): RemoteDataDecision =
        RemoteDataDecisionPolicy.decide(
            isLocal = provider.capabilities.isLocal,
            localOnly = preferences.observe(PreferenceKeys.LOCAL_ONLY_ROUTING).first() ?: false,
            disclosureAccepted = preferences
                .observe(PreferenceKeys.REMOTE_DATA_DISCLOSURE_ACCEPTED)
                .first() ?: false
        )

    fun localOnly(): Flow<Boolean?> = preferences.observe(PreferenceKeys.LOCAL_ONLY_ROUTING)

    fun disclosureAccepted(): Flow<Boolean?> =
        preferences.observe(PreferenceKeys.REMOTE_DATA_DISCLOSURE_ACCEPTED)

    suspend fun setLocalOnly(enabled: Boolean) =
        preferences.set(PreferenceKeys.LOCAL_ONLY_ROUTING, enabled)

    suspend fun acceptRemoteUse() =
        preferences.set(PreferenceKeys.REMOTE_DATA_DISCLOSURE_ACCEPTED, true)
}
