package com.opendash.app.device.provider.mqtt

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import com.opendash.app.device.settings.DeviceSettingsRepository
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttCallback
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import timber.log.Timber

data class MqttConfig(
    val brokerUrl: String = "tcp://localhost:1883",
    val clientId: String = "opendash",
    val username: String = "",
    val password: String = ""
)

data class MqttIncomingMessage(
    val topic: String,
    val payload: String
)

class MqttClientWrapper(
    private val configProvider: suspend () -> MqttConfig
) {

    constructor(config: MqttConfig) : this({ config })

    constructor(settingsRepository: DeviceSettingsRepository) :
        this({ settingsRepository.snapshot().mqtt })

    private var client: MqttClient? = null
    private var connectedConfig: MqttConfig? = null
    private val messageChannel = Channel<MqttIncomingMessage>(Channel.BUFFERED)

    val messages: Flow<MqttIncomingMessage> = messageChannel.receiveAsFlow()

    suspend fun connect(): Boolean {
        return try {
            val config = configProvider()
            if (client?.isConnected == true && connectedConfig == config) {
                return true
            }
            disconnect()
            val mqttClient = MqttClient(config.brokerUrl, config.clientId, MemoryPersistence())
            val options = MqttConnectOptions().apply {
                isAutomaticReconnect = true
                isCleanSession = true
                connectionTimeout = 10
                if (config.username.isNotBlank()) {
                    userName = config.username
                    password = config.password.toCharArray()
                }
            }

            mqttClient.setCallback(object : MqttCallback {
                override fun connectionLost(cause: Throwable?) {
                    Timber.w(cause, "MQTT connection lost")
                }

                override fun messageArrived(topic: String, message: MqttMessage) {
                    messageChannel.trySend(MqttIncomingMessage(topic, String(message.payload)))
                }

                override fun deliveryComplete(token: IMqttDeliveryToken?) {}
            })

            mqttClient.connect(options)
            client = mqttClient
            connectedConfig = config
            Timber.d("MQTT connected to ${config.brokerUrl}")
            true
        } catch (e: Exception) {
            Timber.e(e, "MQTT connection failed")
            false
        }
    }

    fun subscribe(topic: String, qos: Int = 0) {
        try {
            client?.subscribe(topic, qos)
            Timber.d("MQTT subscribed to: $topic")
        } catch (e: Exception) {
            Timber.e(e, "MQTT subscribe failed: $topic")
        }
    }

    fun publish(topic: String, payload: String, qos: Int = 0, retain: Boolean = false): Boolean {
        try {
            val mqttClient = client?.takeIf { it.isConnected } ?: return false
            mqttClient.publish(topic, MqttMessage(payload.toByteArray()).apply {
                this.qos = qos
                isRetained = retain
            })
            return true
        } catch (e: Exception) {
            Timber.e(e, "MQTT publish failed: $topic")
            return false
        }
    }

    fun disconnect() {
        try {
            client?.disconnect()
            client = null
            connectedConfig = null
        } catch (e: Exception) {
            Timber.w(e, "MQTT disconnect error")
        }
    }

    fun isConnected(): Boolean = client?.isConnected == true
}
