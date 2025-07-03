package demo;

import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.xbill.DNS.*;
import org.xbill.DNS.Record;
import javax.net.ssl.SSLPeerUnverifiedException;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

public class TorProxyWithDNS {
	private static Set<String> ipSet = new HashSet<>();
	public static void main(String[] args) {

		while (true) {
			try {
				System.out.println("🔁 Novo ciclo Tor em andamento...");

				// Resolve domínio via Tor DNSPort
				String domain = "httpbin.org";
				String resolvedIp = resolveViaTorDNS(domain);
				System.out.println("🌐 IP resolvido via Tor: " + resolvedIp);

				// Usa IP resolvido com proxy Tor
				sendRequestThroughTor(domain, resolvedIp);

				// Reinicia o circuito Tor
				renewTorCircuit();

				Thread.sleep(15000);  // Aguarda 15 segundos para próximo ciclo
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
	}

	// Resolve domínio via DNSPort do Tor
	public static String resolveViaTorDNS(String domain) throws Exception {
		Resolver resolver = new SimpleResolver("127.0.0.1");
		resolver.setPort(53530);  // Porta DNS configurada no torrc

		Lookup lookup = new Lookup(domain, Type.A);
		lookup.setResolver(resolver);
		Record[] records = lookup.run();

		if (records == null || records.length == 0) {
			throw new IOException("Falha ao resolver domínio via Tor");
		}

		ARecord a = (ARecord) records[0];
		return a.getAddress().getHostAddress();
	}

	// Faz requisição HTTP via proxy SOCKS5 do Tor
	public static void sendRequestThroughTor(String host, String ip) throws IOException {
		Proxy proxy = new Proxy(Proxy.Type.SOCKS, new InetSocketAddress("127.0.0.1", 9050));
		OkHttpClient client = new OkHttpClient.Builder()
				.proxy(proxy)
				.connectTimeout(30, TimeUnit.SECONDS)
				.readTimeout(30, TimeUnit.SECONDS)
				.hostnameVerifier((hostname, session) -> {
					// Permite o IP como destino, desde que o certificado seja para o domínio correto
                    try {
                        return session.getPeerPrincipal().getName().contains("CN=" + host);
                    } catch (SSLPeerUnverifiedException e) {
                        throw new RuntimeException("SSL do site não está compativel com o ip dns do site: " + e);
                    }
                })
				.build();


		HttpUrl url = new Request.Builder().url("https://" + host + "/ip").build().url();

		// Constrói requisição usando IP resolvido, com Host original (SNI e Host header corretos)
        Request.Builder builder = new Request.Builder();
        builder.url(url.newBuilder().host(ip).build());
        builder.header("Host", host);// conecta ao IP
        Request request = builder                    // mas mantém o Host correto na requisição
				.build();

		try (Response response = client.newCall(request).execute()) {

			String bodyString = response.body().string();
			System.out.println("📦 Resposta:");
			System.out.println(bodyString);

			if (ipSet.contains(bodyString)){
				System.out.println("O IP já foi utilizado");
			}

			ipSet.add(bodyString);
			if (ipSet.size() >= 5){

				System.out.println("Removendo limpando lista de IPs utilizados");
				for (String ipUsed : ipSet){
					System.out.println(ipUsed);
				}
				ipSet.clear();
			}
		}
	}

	// Reinicia circuito do Tor via ControlPort
	public static void renewTorCircuit() {
		try (Socket socket = new Socket("127.0.0.1", 9051);
			 PrintWriter writer = new PrintWriter(socket.getOutputStream(), true);
			 Scanner scanner = new Scanner(socket.getInputStream())) {

			writer.println("AUTHENTICATE \"\""); // ou forneça senha se estiver configurado
			String authResponse = scanner.nextLine();
			if (!authResponse.contains("250")) {
				throw new IOException("Falha na autenticação da ControlPort: " + authResponse);
			}

			writer.println("SIGNAL NEWNYM");
			String signalResponse = scanner.nextLine();

			System.out.println("♻️ Circuito reiniciado: " + signalResponse+"\n");
		} catch (Exception e) {
			System.err.println("⚠️ Erro ao renovar circuito Tor:");
			e.printStackTrace();
		}
	}
}
