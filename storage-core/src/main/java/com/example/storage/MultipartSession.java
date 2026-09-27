package com.example.storage;

import java.util.List;

/**
 * Um upload multipart em andamento. Implementações devem ser thread-safe
 * em {@link #uploadPart}, pois partes são enviadas em paralelo.
 */
public interface MultipartSession {

    String key();

    /**
     * Envia uma parte. {@code data} é um buffer reutilizado pelo chamador:
     * a implementação NÃO pode guardar a referência depois de retornar.
     * Pode ser reexecutada (retry) com os mesmos bytes.
     *
     * @param partNumber começa em 1
     */
    UploadedPart uploadPart(int partNumber, byte[] data, int length);

    /** Conclui o upload; as partes podem vir em qualquer ordem. */
    void complete(List<UploadedPart> parts);

    /** Descarta as partes já enviadas. Deve ser idempotente. */
    void abort();
}
