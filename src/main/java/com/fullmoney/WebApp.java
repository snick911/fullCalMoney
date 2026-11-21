package com.fullmoney;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Locale;
import java.util.logging.Logger;

import com.fullmoney.web.WebServer;

/**
 * 網站進入點
 *
 * <p>監聽位址可用系統屬性或環境變數調整：</p>
 * <ul>
 * <li>{@code -Dport=8080} 或環境變數 {@code PORT}，預設 8080</li>
 * <li>{@code -Dhost=0.0.0.0} 或環境變數 {@code HOST}，預設 127.0.0.1（只有本機可連）</li>
 * </ul>
 */
public final class WebApp {

	private static final String DEFAULT_HOST = "127.0.0.1";
	private static final int DEFAULT_PORT = 8080;

	private WebApp() {
	}

	public static void main(String[] args) throws IOException {
		//金額、數字格式固定為繁體中文，不隨伺服器語系改變
		Locale.setDefault(Locale.TAIWAN);
		System.setProperty("java.util.logging.SimpleFormatter.format", "%1$tF %1$tT %4$s %5$s%6$s%n");
		Logger log = Logger.getLogger(WebApp.class.getName());

		String host = setting("host", "HOST", DEFAULT_HOST);
		int port = port(setting("port", "PORT", String.valueOf(DEFAULT_PORT)));
		WebServer server = WebServer.start(new InetSocketAddress(host, port));
		Runtime.getRuntime().addShutdownHook(new Thread(server::stop, "shutdown"));

		String browseHost = "0.0.0.0".equals(host) ? "localhost" : host;
		log.info(() -> "FullMoney " + AppVersion.VERSION + " started: http://" + browseHost + ":" + server.getPort() + "/  (Ctrl+C to stop)");
	}

	private static String setting(String property, String env, String fallback) {
		String value = System.getProperty(property);
		if (value == null || value.isBlank()) {
			value = System.getenv(env);
		}
		return value == null || value.isBlank() ? fallback : value.trim();
	}

	private static int port(String value) {
		try {
			int port = Integer.parseInt(value);
			if (port >= 0 && port <= 65535) {
				return port;
			}
		} catch (NumberFormatException e) {
			//往下拋出說明
		}
		throw new IllegalArgumentException("Invalid port: " + value);
	}
}
