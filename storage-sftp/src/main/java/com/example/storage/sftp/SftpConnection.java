package com.example.storage.sftp;

import com.example.storage.StorageException;
import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.sftp.SFTPClient;

import java.io.Closeable;
import java.io.IOException;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Conexão SSH compartilhada pelos {@link SftpObjectStorage} de um mesmo servidor (um por bucket), com um
 * limite de canais SFTP abertos ao mesmo tempo.
 *
 * <p>Cada operação abre um canal, e cada stream devolvido por {@code open} mantém o seu até ser fechado.
 * O OpenSSH aceita 10 por conexão ({@code MaxSessions}); sem limite, a 11ª operação simultânea falharia com
 * um erro genérico. Aqui ela espera um canal livre por até {@link #ACQUIRE_TIMEOUT}.</p>
 *
 * <p>Com {@link #reconnecting}, uma conexão que caiu (servidor reiniciado, NAT ou firewall que derrubou a
 * sessão ociosa) é refeita na próxima operação; as operações em andamento nela falham. Com {@link #of}, a
 * conexão é de quem a criou e não é refeita.</p>
 */
public final class SftpConnection implements Closeable {

    /** Abaixo dos 10 canais que o OpenSSH aceita por conexão, com folga para outros usos do mesmo cliente. */
    public static final int DEFAULT_MAX_CHANNELS = 8;

    // ponytail: espera fixa por um canal livre; expor na configuração se alguém precisar de outra.
    static final Duration ACQUIRE_TIMEOUT = Duration.ofSeconds(60);

    /** Cria um {@link SSHClient} já conectado e autenticado. */
    @FunctionalInterface
    public interface Connector {
        SSHClient connect() throws IOException;
    }

    private final Connector connector;
    private final Semaphore channels;
    private final int maxChannels;
    private final Duration acquireTimeout;
    private SSHClient client;

    private SftpConnection(SSHClient client, Connector connector, int maxChannels, Duration acquireTimeout) {
        if (maxChannels < 1) {
            throw new IllegalArgumentException("maxChannels deve ser >= 1");
        }
        this.client = client;
        this.connector = connector;
        this.maxChannels = maxChannels;
        this.acquireTimeout = acquireTimeout;
        this.channels = new Semaphore(maxChannels, true);
    }

    /** Usa {@code client} como está, sem reconectar, com {@link #DEFAULT_MAX_CHANNELS}. */
    public static SftpConnection of(SSHClient client) {
        return new SftpConnection(Objects.requireNonNull(client, "client"), null, DEFAULT_MAX_CHANNELS, ACQUIRE_TIMEOUT);
    }

    /** Conecta já (falha cedo se o servidor ou as credenciais estiverem errados) e reconecta quando a conexão cai. */
    public static SftpConnection reconnecting(Connector connector, int maxChannels) throws IOException {
        return reconnecting(connector, maxChannels, ACQUIRE_TIMEOUT);
    }

    static SftpConnection reconnecting(Connector connector, int maxChannels, Duration acquireTimeout)
            throws IOException {
        Objects.requireNonNull(connector, "connector");
        return new SftpConnection(connector.connect(), connector, maxChannels, acquireTimeout);
    }

    /** Um canal SFTP; fechar devolve a vaga. */
    final class Channel implements Closeable {

        private final SFTPClient sftp;
        private boolean closed;

        private Channel(SFTPClient sftp) {
            this.sftp = sftp;
        }

        SFTPClient sftp() {
            return sftp;
        }

        @Override
        public void close() throws IOException {
            if (closed) {
                return;
            }
            closed = true;
            try {
                sftp.close();
            } finally {
                channels.release();
            }
        }
    }

    /**
     * Abre um canal, esperando uma vaga e reconectando se preciso.
     *
     * @throws StorageException nenhuma vaga em {@link #ACQUIRE_TIMEOUT}, ou interrompido esperando
     */
    Channel open() throws IOException {
        acquire();
        try {
            return new Channel(client().newSFTPClient());
        } catch (IOException | RuntimeException e) {
            channels.release();
            throw e;
        }
    }

    /** Host e porta para mensagens e URIs informativas. */
    synchronized SSHClient current() {
        return client;
    }

    private void acquire() {
        try {
            if (!channels.tryAcquire(acquireTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new StorageException("Nenhum dos " + maxChannels + " canais SFTP ficou livre em "
                        + acquireTimeout + "; feche os streams de open() que não estiver usando", null);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new StorageException("Interrompido esperando um canal SFTP livre", e);
        }
    }

    private synchronized SSHClient client() throws IOException {
        if (connector != null && !client.isConnected()) {
            closeQuietly(client);
            client = connector.connect();
        }
        return client;
    }

    @Override
    public synchronized void close() throws IOException {
        client.close();
    }

    private static void closeQuietly(SSHClient client) {
        try {
            client.close();
        } catch (IOException ignored) {
            // a conexão antiga já tinha caído
        }
    }
}
