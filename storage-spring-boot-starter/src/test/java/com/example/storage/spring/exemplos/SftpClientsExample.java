package com.example.storage.spring.exemplos;

import com.example.storage.sftp.SftpConnection;
import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.transport.verification.PromiscuousVerifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.File;
import java.io.IOException;
import java.time.Duration;

/**
 * Como a aplicação declara a {@link SftpConnection} que o starter usa com {@code storage.provider=sftp} (design de
 * 2026-11-15). O starter não conecta, não autentica nem verifica host key; só monta um {@code SftpObjectStorage} por
 * bucket, na subpasta {@code <storage.sftp.root>/<bucket>}.
 *
 * <p>A {@link SftpConnection} limita os canais abertos ({@code maxChannels}, abaixo do {@code MaxSessions} do servidor:
 * 10 no OpenSSH) e, criada com {@code reconnecting}, refaz a conexão quando ela cai. {@code SftpConnection.of(sshClient)}
 * usa um {@link SSHClient} já conectado, sem reconectar. Para autenticar por chave privada, troque o
 * {@code authPassword} por {@code client.authPublickey(username, privateKeyPath)}.</p>
 */
public final class SftpClientsExample {

    private SftpClientsExample() {
    }

    /**
     * @param knownHostsOrNull arquivo {@code known_hosts}; sem valor, {@code ~/.ssh/known_hosts}
     * @param trustAllHosts    pula a verificação de host key (exposto a MITM): só dev e teste
     */
    public static SftpConnection connection(String host, int port, String username, String password,
                                            String knownHostsOrNull, boolean trustAllHosts, Duration keepAlive,
                                            int maxChannels) throws IOException {
        return SftpConnection.reconnecting(
                () -> connect(host, port, username, password, knownHostsOrNull, trustAllHosts, keepAlive), maxChannels);
    }

    private static SSHClient connect(String host, int port, String username, String password,
                                     String knownHostsOrNull, boolean trustAllHosts, Duration keepAlive)
            throws IOException {
        SSHClient client = new SSHClient();
        configureHostKeyVerification(client, knownHostsOrNull, trustAllHosts);
        client.getConnection().getKeepAlive().setKeepAliveInterval((int) keepAlive.toSeconds());
        try {
            client.connect(host, port);
            client.authPassword(username, password);
        } catch (IOException | RuntimeException e) {
            closeQuietly(client);
            throw e;
        }
        return client;
    }

    /** Sem {@code known_hosts} nem {@code trustAllHosts}, falha cedo em vez de aceitar qualquer host key. */
    private static void configureHostKeyVerification(SSHClient client, String knownHostsOrNull, boolean trustAllHosts)
            throws IOException {
        if (knownHostsOrNull != null) {
            client.loadKnownHosts(new File(knownHostsOrNull));
        } else if (trustAllHosts) {
            client.addHostKeyVerifier(new PromiscuousVerifier());
        } else {
            client.loadKnownHosts();
        }
    }

    private static void closeQuietly(SSHClient client) {
        try {
            client.close();
        } catch (IOException ignored) {
            // a falha que importa é a original da conexão ou da autenticação
        }
    }

    /** Produção: {@code known_hosts} do servidor e senha vinda do ambiente. */
    @Configuration(proxyBeanMethods = false)
    public static class Producao {

        @Bean(destroyMethod = "close")
        SftpConnection sftpConnection(@Value("${sftp.host}") String host, @Value("${sftp.username}") String username,
                                      @Value("${sftp.password}") String password,
                                      @Value("${sftp.known-hosts}") String knownHosts) throws IOException {
            return SftpClientsExample.connection(host, 22, username, password, knownHosts, false,
                    Duration.ofSeconds(30), 8);
        }
    }
}
