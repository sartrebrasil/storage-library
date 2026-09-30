package com.example.storage;

import java.io.IOException;
import java.util.List;

/**
 * Como as partes de um {@link MultipartOutputStream} chegam ao storage: na thread que escreve
 * ({@link SequentialPartUploader}) ou em background ({@link ParallelPartUploader}). O stream só
 * enche buffers; o uploader envia e decide em qual buffer a escrita continua.
 */
interface PartUploader {

    static PartUploader of(MultipartSession session, MultipartConfig config) {
        return config.isSequential()
                ? new SequentialPartUploader(session)
                : new ParallelPartUploader(session, config);
    }

    /**
     * Buffer onde o stream continua escrevendo depois de enviar {@code sent}. No modo paralelo
     * pode bloquear: é o backpressure.
     */
    byte[] nextBuffer(byte[] sent) throws IOException;

    /** Envia os primeiros {@code length} bytes de {@code buffer} como a parte {@code partNumber}. */
    void send(int partNumber, byte[] buffer, int length) throws IOException;

    /** Espera todas as partes enviadas e as devolve. */
    List<UploadedPart> awaitAll() throws IOException;

    /** Espera as partes em voo terminarem, com sucesso ou falha, sem lançar. Usado antes do abort. */
    void drain();

    static String describe(MultipartSession session) {
        return session.key() + " (uploadId " + session.uploadId() + ")";
    }
}
