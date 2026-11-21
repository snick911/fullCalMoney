package com.fullmoney.web;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import com.fullmoney.web.api.ApiEndpoints;
import com.sun.net.httpserver.HttpServer;

/**
 * 以 JDK 內建的 HttpServer 提供網頁與試算 API，不需要任何第三方套件
 */
public final class WebServer {

	private static final int STOP_DELAY_SECONDS = 1;

	private final HttpServer server;
	private final ExecutorService executor;

	private WebServer(HttpServer server, ExecutorService executor) {
		this.server = server;
		this.executor = executor;
	}

	public static WebServer start(InetSocketAddress address) throws IOException {
		HttpServer server = HttpServer.create(address, 0);
		for (ApiEndpoint endpoint : ApiEndpoints.all()) {
			server.createContext(endpoint.getPath(), new ApiHandler(endpoint));
		}
		server.createContext("/", new StaticHandler("/static"));

		AtomicInteger threadCount = new AtomicInteger();
		ExecutorService executor = Executors.newFixedThreadPool(Math.max(4, Runtime.getRuntime().availableProcessors() * 2), task -> {
			Thread thread = new Thread(task, "http-" + threadCount.incrementAndGet());
			thread.setDaemon(true);
			return thread;
		});
		server.setExecutor(executor);
		server.start();
		return new WebServer(server, executor);
	}

	public int getPort() {
		return server.getAddress().getPort();
	}

	public void stop() {
		server.stop(STOP_DELAY_SECONDS);
		executor.shutdownNow();
	}
}
