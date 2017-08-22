/*******************************************************************************
 * Copyright (c) 2015 Institute for Pervasive Computing, ETH Zurich and others.
 * 
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * and Eclipse Distribution License v1.0 which accompany this distribution.
 * 
 * The Eclipse Public License is available at
 *    http://www.eclipse.org/legal/epl-v10.html
 * and the Eclipse Distribution License is available at
 *    http://www.eclipse.org/org/documents/edl-v10.html.
 * 
 * Contributors:
 *    Matthias Kovatsch - creator and main architect
 *    Stefan Jucker - DTLS implementation
 *    Achim Kraus (Bosch Software Innovations GmbH) - add support for multiple clients
 *                                                    exchange multiple messages
 ******************************************************************************/
package org.eclipse.californium.scandium.examples;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.eclipse.californium.elements.RawData;
import org.eclipse.californium.elements.RawDataChannel;
import org.eclipse.californium.elements.util.DaemonThreadFactory;
import org.eclipse.californium.scandium.DTLSConnector;
import org.eclipse.californium.scandium.ScandiumLogger;
import org.eclipse.californium.scandium.config.DtlsConnectorConfig;
import org.eclipse.californium.scandium.dtls.pskstore.StaticPskStore;

public class ExampleDTLSClient {

	static {
		ScandiumLogger.initialize();
		ScandiumLogger.setLevel(Level.WARNING);
	}

	private static final int DEFAULT_PORT = 5684;
	private static final long DEFAULT_TIMEOUT_NANOS = TimeUnit.MILLISECONDS.toNanos(10000);
	private static final Logger LOG = Logger.getLogger(ExampleDTLSClient.class.getName());
	private static final String TRUST_STORE_PASSWORD = "rootPass";
	private static final String KEY_STORE_PASSWORD = "endPass";
	private static final String KEY_STORE_LOCATION = "certs/keyStore.jks";
	private static final String TRUST_STORE_LOCATION = "certs/trustStore.jks";

	private static int maxMessages = 0;
	private static CountDownLatch messageCounter;

	private DTLSConnector dtlsConnector;

	public ExampleDTLSClient() {
		InputStream inTrust = null;
		InputStream in = null;
		try {
			// load key store
			KeyStore keyStore = KeyStore.getInstance("JKS");
			in = getClass().getClassLoader().getResourceAsStream(KEY_STORE_LOCATION);
			keyStore.load(in, KEY_STORE_PASSWORD.toCharArray());
			in.close();

			// load trust store
			KeyStore trustStore = KeyStore.getInstance("JKS");
			inTrust = getClass().getClassLoader().getResourceAsStream(TRUST_STORE_LOCATION);
			trustStore.load(inTrust, TRUST_STORE_PASSWORD.toCharArray());

			// You can load multiple certificates if needed
			Certificate[] trustedCertificates = new Certificate[1];
			trustedCertificates[0] = trustStore.getCertificate("root");

			DtlsConnectorConfig.Builder builder = new DtlsConnectorConfig.Builder();
			builder.setPskStore(new StaticPskStore("Client_identity", "secretPSK".getBytes()));
			builder.setIdentity((PrivateKey) keyStore.getKey("client", KEY_STORE_PASSWORD.toCharArray()),
					keyStore.getCertificateChain("client"), true);
			builder.setTrustStore(trustedCertificates);
			builder.setEnableAddressReuse(false);
			builder.setConnectionThreadCount(2);
			dtlsConnector = new DTLSConnector(builder.build());
			dtlsConnector.setRawDataReceiver(new RawDataChannel() {

				@Override
				public void receiveData(RawData raw) {
					if (dtlsConnector.isRunning()) {
						receive(raw);
					}
				}
			});

		} catch (GeneralSecurityException | IOException e) {
			LOG.log(Level.SEVERE, "Could not load the keystore", e);
		} finally {
			try {
				if (inTrust != null) {
					inTrust.close();
				}
				if (in != null) {
					in.close();
				}
			} catch (IOException e) {
				LOG.log(Level.SEVERE, "Cannot close key store file", e);
			}
		}
	}

	private void receive(RawData raw) {

		messageCounter.countDown();
		long c = messageCounter.getCount();
		if (LOG.isLoggable(Level.INFO)) {
			LOG.log(Level.INFO, "Received response: {0} {1}", new Object[] { new String(raw.getBytes()), c });
		}
		if (0 < c) {
			try {
				dtlsConnector.send(new RawData(("HELLO WORLD " + c + ".").getBytes(), raw.getInetSocketAddress()));
			} catch (IllegalStateException e) {
				LOG.log(Level.FINER, "send failed after " + (c - 1) + " messages!", e);
			}
		} else {
			dtlsConnector.destroy();
		}

	}

	private void start() {
		try {
			dtlsConnector.start();
		} catch (IOException e) {
			LOG.log(Level.SEVERE, "Cannot start connector", e);
		}
	}

	private void startTest(InetSocketAddress peer) {
		dtlsConnector.send(new RawData("HELLO WORLD".getBytes(), peer));
	}

	private void stop() {
		if (dtlsConnector.isRunning()) {
			dtlsConnector.destroy();
		}
	}

	public static void main(String[] args) throws InterruptedException {
		int clients = 1;
		int messages = 100;
		if (0 < args.length) {
			clients = Integer.parseInt(args[0]);
			if (1 < args.length) {
				messages = Integer.parseInt(args[1]);
			}
		}
		maxMessages = (messages * clients);
		messageCounter = new CountDownLatch(maxMessages);
		List<ExampleDTLSClient> clientList = new ArrayList<>(clients);
		ExecutorService executor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors(),
				new DaemonThreadFactory("Aux#"));

		System.out.println("Create " + clients + " DTLS example clients.");

		final CountDownLatch start = new CountDownLatch(clients);

		// Create & start clients
		for (int index = 0; index < clients; ++index) {
			final ExampleDTLSClient client = new ExampleDTLSClient();
			clientList.add(client);
			executor.execute(new Runnable() {

				@Override
				public void run() {
					client.start();
					start.countDown();
				}
			});
		}
		start.await();
		System.out.println(clients + " DTLS example clients started.");

		// Get peer address
		InetSocketAddress peer;
		if (args.length == 4) {
			peer = new InetSocketAddress(args[2], Integer.parseInt(args[3]));
		} else {
			peer = new InetSocketAddress(InetAddress.getLoopbackAddress(), DEFAULT_PORT);
		}

		// Start Test
		long nanos = System.nanoTime();
		long lastMessageCountDown = messageCounter.getCount();

		for (ExampleDTLSClient client : clientList) {
			client.startTest(peer);
		}

		// Wait with timeout or all messages send.
		while (!messageCounter.await(DEFAULT_TIMEOUT_NANOS, TimeUnit.NANOSECONDS)) {
			long current = messageCounter.getCount();
			if (lastMessageCountDown == current && current < maxMessages) {
				// no new messages, clients are stale
				// adjust start time with timeout
				nanos += DEFAULT_TIMEOUT_NANOS; 
				break;
			}
			lastMessageCountDown = current;
		}
		long count = maxMessages - messageCounter.getCount();
		nanos = System.nanoTime() - nanos;

		System.out.println(clients + " DTLS example clients finished.");

		for (ExampleDTLSClient client : clientList) {
			client.stop();
		}

		System.out.println(count + " messages received, " + (maxMessages) + " expected");
		System.out.println(count + " messages in " + TimeUnit.NANOSECONDS.toMillis(nanos) + " ms");
		System.out.println((count * 1000) / TimeUnit.NANOSECONDS.toMillis(nanos) + " messages per s");
		if (count < maxMessages) {
			System.out.println("Stale at " + lastMessageCountDown + " messages");
		}
	}
}
