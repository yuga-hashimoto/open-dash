package com.opensmarthome.speaker.ui.settings.sysinfo

import com.google.common.truth.Truth.assertThat
import com.opensmarthome.speaker.assistant.provider.AssistantProvider
import com.opensmarthome.speaker.assistant.router.ConversationRouter
import com.opensmarthome.speaker.assistant.skills.SkillRegistry
import com.opensmarthome.speaker.data.db.MemoryDao
import com.opensmarthome.speaker.data.db.MultiroomRejectionDao
import com.opensmarthome.speaker.data.db.MultiroomRejectionEntity
import com.opensmarthome.speaker.data.db.MultiroomTrafficDao
import com.opensmarthome.speaker.data.db.MultiroomTrafficEntity
import com.opensmarthome.speaker.data.db.RoutineDao
import com.opensmarthome.speaker.device.DeviceManager
import com.opensmarthome.speaker.multiroom.PeerFreshness
import com.opensmarthome.speaker.multiroom.PeerLivenessTracker
import com.opensmarthome.speaker.tool.ToolExecutor
import com.opensmarthome.speaker.tool.rag.RagRepository
import com.opensmarthome.speaker.util.MulticastDiscovery
import com.opensmarthome.speaker.util.NetworkMonitor
import com.opensmarthome.speaker.util.ThermalLevel
import com.opensmarthome.speaker.util.ThermalMonitor
import com.opensmarthome.speaker.voice.metrics.LatencyRecorder
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Covers [SystemInfoViewModel.clearMultiroomCounters] — the entry point
 * the user taps from the System Info screen to wipe the lifetime traffic
 * and rejection tables. The refresh() path is otherwise covered by
 * integration testing; this test uses hand-rolled fake DAOs so the clear
 * behaviour is observed end-to-end (insert → clear → empty-state
 * snapshot) without Room.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SystemInfoViewModelClearCountersTest {

    private val testDispatcher = StandardTestDispatcher()

    @BeforeEach fun setup() { Dispatchers.setMain(testDispatcher) }
    @AfterEach fun teardown() { Dispatchers.resetMain() }

    private class FakeTrafficDao : MultiroomTrafficDao {
        private val rows = MutableStateFlow<List<MultiroomTrafficEntity>>(emptyList())
        private val mutex = Mutex()
        override suspend fun upsertIncrement(type: String, direction: String, nowMs: Long) {
            mutex.withLock {
                rows.value = rows.value + MultiroomTrafficEntity(
                    type = type, direction = direction, count = 1L, lastAtMs = nowMs
                )
            }
        }
        override fun observe(): Flow<List<MultiroomTrafficEntity>> = rows
        override suspend fun listAll(): List<MultiroomTrafficEntity> = rows.value
        override suspend fun clear() { rows.value = emptyList() }
    }

    private class FakeRejectionDao : MultiroomRejectionDao {
        private val rows = MutableStateFlow<List<MultiroomRejectionEntity>>(emptyList())
        private val mutex = Mutex()
        override suspend fun upsertIncrement(reason: String, nowMs: Long) {
            mutex.withLock {
                rows.value = rows.value + MultiroomRejectionEntity(
                    reason = reason, count = 1L, lastAtMs = nowMs
                )
            }
        }
        override fun observe(): Flow<List<MultiroomRejectionEntity>> = rows
        override suspend fun listAll(): List<MultiroomRejectionEntity> = rows.value
        override suspend fun clear() { rows.value = emptyList() }
    }

    @Test
    fun `clearMultiroomCounters wipes both tables and refreshes snapshot`() = runTest {
        val trafficDao = FakeTrafficDao()
        val rejectionDao = FakeRejectionDao()
        trafficDao.upsertIncrement(type = "TTS_BROADCAST", direction = "in", nowMs = 1L)
        rejectionDao.upsertIncrement(reason = "HMAC_MISMATCH", nowMs = 2L)

        val vm = buildVm(trafficDao = trafficDao, rejectionDao = rejectionDao)
        advanceUntilIdle()

        // Sanity — the initial refresh populated both tables.
        assertThat(vm.state.value.multiroomTraffic).isNotEmpty()
        assertThat(vm.state.value.rejections).isNotEmpty()

        vm.clearMultiroomCounters()
        advanceUntilIdle()

        assertThat(vm.state.value.multiroomTraffic).isEmpty()
        assertThat(vm.state.value.rejections).isEmpty()
        assertThat(trafficDao.listAll()).isEmpty()
        assertThat(rejectionDao.listAll()).isEmpty()
    }

    private fun buildVm(
        trafficDao: MultiroomTrafficDao,
        rejectionDao: MultiroomRejectionDao
    ): SystemInfoViewModel {
        val deviceManager = mockk<DeviceManager>()
        every { deviceManager.devices } returns MutableStateFlow(emptyMap())

        val skillRegistry = mockk<SkillRegistry>()
        every { skillRegistry.all() } returns emptyList()

        val memoryDao = mockk<MemoryDao>()
        coEvery { memoryDao.list(any()) } returns emptyList()

        val router = mockk<ConversationRouter>()
        every { router.activeProvider } returns MutableStateFlow<AssistantProvider?>(null)
        every { router.availableProviders } returns MutableStateFlow(emptyList())

        val networkMonitor = mockk<NetworkMonitor>()
        every { networkMonitor.isOnline } returns MutableStateFlow(true)

        val latencyRecorder = mockk<LatencyRecorder>()
        every { latencyRecorder.budgetViolations() } returns emptyMap()
        every { latencyRecorder.totalMeasurements() } returns 0L

        val toolExecutor = mockk<ToolExecutor>()
        coEvery { toolExecutor.availableTools() } returns emptyList()

        val routineDao = mockk<RoutineDao>()
        coEvery { routineDao.listAll() } returns emptyList()

        val ragRepository = mockk<RagRepository>()
        coEvery { ragRepository.listDocuments() } returns emptyList()

        val multicastDiscovery = mockk<MulticastDiscovery>()
        every { multicastDiscovery.speakers } returns MutableStateFlow(emptyList())
        every { multicastDiscovery.registeredName } returns MutableStateFlow(null)

        val thermalMonitor = mockk<ThermalMonitor>()
        every { thermalMonitor.status } returns MutableStateFlow(ThermalLevel.NORMAL)

        val peerLivenessTracker = mockk<PeerLivenessTracker>()
        every { peerLivenessTracker.staleness } returns MutableStateFlow<Map<String, PeerFreshness>>(emptyMap())

        return SystemInfoViewModel(
            deviceManager = deviceManager,
            skillRegistry = skillRegistry,
            memoryDao = memoryDao,
            router = router,
            networkMonitor = networkMonitor,
            latencyRecorder = latencyRecorder,
            toolExecutor = toolExecutor,
            routineDao = routineDao,
            ragRepository = ragRepository,
            multicastDiscovery = multicastDiscovery,
            thermalMonitor = thermalMonitor,
            peerLivenessTracker = peerLivenessTracker,
            multiroomTrafficDao = trafficDao,
            multiroomRejectionDao = rejectionDao
        )
    }
}
