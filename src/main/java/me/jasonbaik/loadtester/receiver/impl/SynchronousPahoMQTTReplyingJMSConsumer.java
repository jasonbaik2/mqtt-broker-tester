package me.jasonbaik.loadtester.receiver.impl;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Properties;
import java.util.UUID;

import javax.jms.BytesMessage;
import javax.jms.Connection;
import javax.jms.ConnectionFactory;
import javax.jms.JMSException;
import javax.jms.Message;
import javax.jms.MessageConsumer;
import javax.jms.MessageListener;
import javax.jms.Session;

import me.jasonbaik.loadtester.constant.StringConstants;
import me.jasonbaik.loadtester.receiver.Receiver;
import me.jasonbaik.loadtester.valueobject.Payload;
import me.jasonbaik.loadtester.valueobject.ReportData;

import org.apache.activemq.ActiveMQConnectionFactory;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.MqttSecurityException;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

public class SynchronousPahoMQTTReplyingJMSConsumer extends Receiver<SynchronousPahoMQTTReplyingJMSConsumerConfig> implements MessageListener {

	private static final Logger logger = LogManager.getLogger(SynchronousPahoMQTTReplyingJMSConsumer.class);

	private String uuid = UUID.randomUUID().toString();
	private ConnectionFactory connFactory;
	private Connection conn;
	private Session session;
	private MessageConsumer consumer;
	private Map<String, Long> inTimes = Collections.synchronizedMap(new HashMap<String, Long>());

	private MqttClient mqttClient;

	private int publishedCount;
	private int successCount;
	private int failureCount;

	public SynchronousPahoMQTTReplyingJMSConsumer(SynchronousPahoMQTTReplyingJMSConsumerConfig config) {
		super(config);
	}

	private class MessageCallback implements MqttCallback {

		private MqttClient client;
		private MqttConnectOptions connectOptions;

		public MessageCallback(MqttClient client, MqttConnectOptions connectOptions) {
			super();
			this.client = client;
			this.connectOptions = connectOptions;
		}

		@Override
		public void connectionLost(Throwable cause) {
			while (!client.isConnected()) {
				logger.error("Connection lost. Reconnecting after 5 seconds...", cause);

				try {
					Thread.sleep(5000);

					String[] rotatedBrokers = rotateBrokers(this.connectOptions.getServerURIs());
					logger.info("Reconnecting to: " + rotatedBrokers[0]);
					this.connectOptions.setServerURIs(rotatedBrokers);
					this.client.connect(this.connectOptions);
					break;

				} catch (MqttSecurityException e) {
					logger.error(e);
				} catch (MqttException e) {
					logger.error(e);
				} catch (InterruptedException e) {
					logger.error(e);
				}
			}
		}

		@Override
		public void messageArrived(String topic, MqttMessage message) throws Exception {
			// TODO Auto-generated method stub

		}

		@Override
		public void deliveryComplete(IMqttDeliveryToken token) {
			// TODO Auto-generated method stub

		}

	};

	@Override
	public void init() throws Exception {
		connFactory = new ActiveMQConnectionFactory(getConfig().getJmsBrokerUsername(), getConfig().getJmsBrokerPassword(), getConfig().getJmsBroker());
		conn = connFactory.createConnection();
		session = conn.createSession(false, Session.AUTO_ACKNOWLEDGE);
		consumer = session.createConsumer(session.createQueue(getConfig().getQueue()));

		logger.info("Successfully established a JMS connection");

		mqttClient = new MqttClient(getNextBroker(), uuid, new MemoryPersistence());

		MqttConnectOptions options = new MqttConnectOptions();

		if (getConfig().getMqttBrokers() != null) {
			options.setServerURIs(getConfig().getMqttBrokers());
		}

		options.setCleanSession(getConfig().isCleanSession());
		options.setUserName(getConfig().getMqttBrokerUsername());
		options.setPassword(getConfig().getMqttBrokerPassword().toCharArray());
		options.setKeepAliveInterval(0);

		Properties props = new Properties();
		props.putAll(getConfig().getSslProperties());
		options.setSSLProperties(props);

		mqttClient.setCallback(new MessageCallback(mqttClient, options));
		mqttClient.connect(options);

		logger.info("Successfully established an MQTT connection");
	}

	private int brokerIndex = 0;

	private String getNextBroker() {
		if (getConfig().getMqttBrokers() != null) {
			return getConfig().getMqttBrokers()[brokerIndex++ % getConfig().getMqttBrokers().length];
		}
		return getConfig().getMqttBroker();
	}

	static String[] rotateBrokers(String[] brokers) {
		String[] rotated = new String[brokers.length];
		System.arraycopy(brokers, 1, rotated, 0, brokers.length - 1);
		rotated[brokers.length - 1] = brokers[0];
		return rotated;
	}

	@Override
	public void destroy() throws JMSException, MqttException {
		consumer.close();
		session.close();
		conn.close();

		mqttClient.disconnect();
	}

	@Override
	public void receive() throws JMSException {
		consumer.setMessageListener(this);
		conn.start();
	}

	@Override
	public synchronized void onMessage(Message message) {

		if (message instanceof BytesMessage) {
			BytesMessage bytesMessage = (BytesMessage) message;

			try {
				byte[] payload = new byte[(int) bytesMessage.getBodyLength()];
				bytesMessage.readBytes(payload);

				String[] idPair = Payload.extractIdPair(payload);
				String mqttReplyTopic = idPair[0];
				int retryAttempts = 0;

				logger.debug("Publishing a reply to the MQTT client uuid=" + mqttReplyTopic);

				while (true) {
					try {
						mqttClient.publish(mqttReplyTopic, payload, getConfig().getQos(), false);
						publishedCount++;
						printCounts(true);
						break;

					} catch (MqttException e) {
						if (retryAttempts++ < getConfig().getRetryAttempts()) {
							logger.error("Failed to publish a reply. Retrying after 5 seconds...", e);

							try {
								Thread.sleep(5000);
							} catch (InterruptedException ie) {
								logger.error(ie);
							}

							continue;

						} else {
							printCounts(false);
							break;
						}
					}
				}

				inTimes.put(Payload.extractUniqueId(payload), message.getLongProperty(StringConstants.JMSACTIVEMQBROKERINTIME));

			} catch (JMSException e) {
				throw new IllegalArgumentException();
			}
		} else {
			throw new IllegalArgumentException();
		}
	}

	private synchronized void printCounts(boolean success) {
		if (success) {
			successCount++;
		} else {
			failureCount++;
		}

		logger.info("Published: " + publishedCount + ", " + "Success: " + successCount + ", Failed: " + failureCount);
	}

	@Override
	public ArrayList<ReportData> report() throws InterruptedException {
		StringBuilder sb = new StringBuilder(StringConstants.JMSACTIVEMQBROKERINTIME).append("\n");

		synchronized (inTimes) {
			for (Iterator<Entry<String, Long>> iter = inTimes.entrySet().iterator(); iter.hasNext();) {
				Entry<String, Long> entry = iter.next();
				sb.append(entry.getKey()).append(",").append(entry.getValue()).append("\n");
			}
		}

		return new ArrayList<ReportData>(Arrays.asList(new ReportData[] { new ReportData("SynchronousPahoMQTTReplyingJMSConsumer_JMS_In_Times.csv", sb.toString().getBytes()) }));
	}

}