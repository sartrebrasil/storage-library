package com.example.storage.sftp;

import com.example.storage.ObjectStorage;
import com.example.storage.PutOptions;
import net.schmizz.sshj.SSHClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

/** Validação de chave/root: roda sem servidor, o mock de {@link SSHClient} nunca é chamado. */
class SftpOperationsTest {

    private ObjectStorage storage;

    @BeforeEach
    void setUp() {
        storage = new SftpObjectStorage(mock(SSHClient.class), "/upload");
    }

    @Test
    void rootPrecisaSerCaminhoAbsoluto() {
        assertThrows(IllegalArgumentException.class, () -> new SftpObjectStorage(mock(SSHClient.class), "relativo"));
    }

    @Test
    void chaveComPathTraversalEhRejeitada() {
        assertThrows(IllegalArgumentException.class,
                () -> storage.put("../fora.txt", new byte[0], PutOptions.of("text/plain")));
        assertThrows(IllegalArgumentException.class,
                () -> storage.put("a/../../fora.txt", new byte[0], PutOptions.of("text/plain")));
        assertThrows(IllegalArgumentException.class,
                () -> storage.put("a//b.txt", new byte[0], PutOptions.of("text/plain")));
    }

    @Test
    void chaveNoPrefixoReservadoEhRejeitada() {
        assertThrows(IllegalArgumentException.class,
                () -> storage.put(".uploads/x", new byte[0], PutOptions.of("text/plain")));
        assertThrows(IllegalArgumentException.class,
                () -> storage.put(".uploads", new byte[0], PutOptions.of("text/plain")));
    }

    @Test
    void chaveComNomeReservadoDoSidecarOuTemporarioEhRejeitada() {
        assertThrows(IllegalArgumentException.class,
                () -> storage.put("a.txt.objmeta", new byte[0], PutOptions.of("text/plain")));
        assertThrows(IllegalArgumentException.class,
                () -> storage.put("dir/.pending-x", new byte[0], PutOptions.of("text/plain")));
    }

    @Test
    void chaveComBarraInvertidaEhRejeitada() {
        assertThrows(IllegalArgumentException.class,
                () -> storage.put("a\\b.txt", new byte[0], PutOptions.of("text/plain")));
    }

    @Test
    void chaveVaziaOuAbsolutaEhRejeitada() {
        assertThrows(IllegalArgumentException.class,
                () -> storage.put("", new byte[0], PutOptions.of("text/plain")));
        assertThrows(IllegalArgumentException.class,
                () -> storage.put("/absoluta.txt", new byte[0], PutOptions.of("text/plain")));
    }
}
