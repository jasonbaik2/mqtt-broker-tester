package me.jasonbaik.loadtester.sender.impl;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import me.jasonbaik.loadtester.client.MQTTClientFactory;
import me.jasonbaik.loadtester.util.MQTTFlightTracer;
import me.jasonbaik.loadtester.util.SSLUtil;
import me.jasonbaik.loadtester.valueobject.Broker;
import me.jasonbaik.loadtester.valueobject.MQTTFlightData;
import me.jasonbaik.loadtester.valueobject.Payload;
import me.jasonbaik.loadtester.valueobject.ReportData;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.fusesource.hawtbuf.Buffer;
import org.fusesource.hawtbuf.UTF8Buffer;
import org.fusesource.mqtt.client.Callback;
import org.fusesource.mqtt.client.CallbackConnection;
import org.fusesource.mqtt.client.ExtendedListener;
import org.fusesource.mqtt.client.MQTT;
import org.fusesource.mqtt.client.Topic;

public class RoundRobinMQTTPublisher extends AbstractRoundRobinMQTTPublisher<RoundRobinMQTTPublisherConfig> {

	private static final Logger logger = LogManager.getLogger(RoundRobinMQTTPublisher.class);

	private final String uuid = UUID.randomUUID().toString();

	private CountDownLatch connectionLatch;
	private List<CallbackConnection> connections;
	private List<String> topicMismatchedMsgs = new LinkedList<String>();
	private List<MQTTFlightTracer> tracers;

	class ConnectCallback implements Callback<Void> {

		MQTT client;
		CallbackConnection conn;

		private ConnectCallback(MQTT client, CallbackConnection conn) {
			super();
			this.client = client;
			this.conn = conn;
		}

		@Override
		public void onSuccess(Void value) {
			conn.subscribe(new Topic[] { new Topic(client.getClientId().toString(), getConfig().getQos()) }, subscribeCallback);
		}

		@Override
		public void onFailure(Throwable value) {
			logger.error(value);
		}

	};

	private Callback<byte[]> subscribeCallback = new Callback<byte[]>() {

		@Override
		public void onSuccess(byte[] value) {
			connectionLatch.countDown();
		}

		@Override
		public void onFailure(Throwable value) {
			logger.error(value);
		}

	};

	private ExtendedListener connectionListener = new ExtendedListener() {

		@Override
		public void onPublish(UTF8Buffer topic, Buffer body, Runnable ack) {
			getRepliedCount().incrementAndGet();
			log(topic, body);
			ack.run();
		}

		@Override
		public void onFailure(Throwable value) {
			// TODO Auto-generated method stub

		}

		@Override
		public void onDisconnected() {
			// TODO Auto-generated method stub

		}

		@Override
		public void onConnected() {
			// TODO Auto-generated method stub

		}

		@Override
		public void onPublish(UTF8Buffer topic, Buffer body, Callback<Callback<Void>> ack) {
			getRepliedCount().incrementAndGet();
			log(topic, body);
			ack.onSuccess(null);
		}

		private void log(UTF8Buffer topic, Buffer body) {
			if (logger.isDebugEnabled()) {
				logger.debug("Received a reply on connection=" + topic + " for message id=" + Payload.extractMessageId(body.toByteArray()));
			}

			if (!topic.toString().equals(Payload.extractConnectionId(body.toByteArray()))) {
				logger.error("Received a message sent from the connection: " + Payload.extractConnectionId(body.toByteArray()) + " to " + topic);

				synchronized (topicMismatchedMsgs) {
					topicMismatchedMsgs.add(Payload.extractUniqueId(body.toByteArray()));
				}
			}
		}

	};

	private Callback<Void> publishCallback = new Callback<Void>() {

		@Override
		public void onSuccess(Void value) {
			getSuccessCount().incrementAndGet();
		}

		@Override
		public void onFailure(Throwable value) {
			getFailureCount().incrementAndGet();
		}

	};

	public RoundRobinMQTTPublisher(RoundRobinMQTTPublisherConfig config) {
		super(config);
		// TODO Auto-generated constructor stub
	}

	@Override
	public void init() throws Exception {
		super.init();

		connections = Collections.synchronizedList(new ArrayList<CallbackConnection>(getConfig().getNumConnections()));
		tracers = Collections.synchronizedList(new ArrayList<MQTTFlightTracer>(getConfig().getNumConnections()));
		connectionLatch = new CountDownLatch(getConfig().getNumConnections());
	}

	@Override
	protected void connect() throws Exception {
		logger.info("Initiating " + getConfig().getNumConnections() + " connections with a step size of " + getConfig().getConnectionStepSize() + " and a step interval of "
				+ getConfig().getConnectionStepIntervalMilli() + "ms...");

		for (int i = 0; i < getConfig().getNumConnections(); i++) {
			MQTT client = new MQTT();
			Broker broker = getNextBroker();
			client.setHost(MQTTClientFactory.getFusesourceConnectionUrl(broker, getConfig().isSsl()));
			client.setClientId(uuid + "-" + i);
			client.setCleanSession(getConfig().isCleanSession());
			client.setUserName(broker.getUsername());
			client.setPassword(broker.getPassword());
			client.setKeepAlive((short) (getConfig().getKeepAliveIntervalMilli() / 1000));
			client.setSslContext(SSLUtil.createSSLContext(getConfig().getKeyStore(), getConfig().getKeyStorePassword(), getConfig().getTrustStore(), getConfig().getTrustStorePassword()));

			MQTTFlightTracer tracer = new MQTTFlightTracer();
			client.setTracer(tracer);
			tracers.add(tracer);

			CallbackConnection conn = client.callbackConnection();
			conn.listener(connectionListener);
			conn.connect(new ConnectCallback(client, conn));

			connections.add(conn);

			if (i != 0 && i % getConfig().getConnectionStepSize() == 0) {
				logger.info("Sleeping for " + getConfig().getConnectionStepIntervalMilli() + "ms to allow the last " + getConfig().getConnectionStepSize() + " connections to be established");
				Thread.sleep(getConfig().getConnectionStepIntervalMilli());
			}
		}

		while (!connectionLatch.await(10, TimeUnit.SECONDS)) {
			logger.info(connectionLatch.getCount() + " connections are still yet to be established");
			logger.info("Waiting another 10 seconds until all connections are established");
		}

		logger.info("Successfully established all " + getConfig().getNumConnections() + " connections");
	}

	protected void roundRobinSend(int index, byte[] payload) {
		int cIndex = getClientIndex().getAndIncrement() % connections.size();
		String connectionId = uuid + "-" + (cIndex);

		logger.debug("Publishing a message using the client #" + cIndex);
		connections.get(cIndex).publish(getConfig().getTopic(), Payload.toBytes(connectionId, index, payload), getConfig().getQos(), false, publishCallback);
		getPublishedCount().incrementAndGet();
	}

	@Override
	public ArrayList<ReportData> report() {
		List<MQTTFlightData> flightData = new LinkedList<MQTTFlightData>();

		for (MQTTFlightTracer tracer : this.tracers) {
			flightData.addAll(tracer.getFlightData());
		}

		StringBuilder sb = new StringBuilder();

		for (String m : topicMismatchedMsgs) {
			sb.append(m).append("\n");
		}

		return new ArrayList<ReportData>(Arrays.asList(new ReportData[] { new ReportData("RoundRobinMQTTPublisher_MQTT_Flight_Data.csv", MQTTFlightTracer.toCsv(flightData)),
				new ReportData("Topic_Mismatched_Messages.csv", sb.toString().getBytes()) }));
	}

	@Override
	public void destroy() {
		for (CallbackConnection conn : connections) {
			conn.kill(null);
		}

		connections.clear();
		tracers.clear();
	}

	@Override
	public void log() {
		System.out.print(getState());
		System.out.print("\tPublished: ");
		System.out.print(getPublishedCount());
		System.out.print(", Replied: ");
		System.out.print(getRepliedCount());
		System.out.print(", Success: ");
		System.out.print(getSuccessCount());
		System.out.print(", Failed: ");
		System.out.print(getFailureCount());
		System.out.print("\n");
	}

}