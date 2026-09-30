package com.example.storage;

import java.util.List;

/**
 * Um upload multipart em andamento. Implementações devem ser thread-safe
 * em {@link #uploadPart}, pois partes são enviadas em paralelo.
 */
public interface MultipartSession {

    String key();

    /**
     * Identifica o upload em logs e mensagens de erro. É o id nativo do provedor
     * (S3, GCS, OCI) ou, onde ele não existe (Azure), o id gerado pela sessão.
     */
    String uploadId();

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

    /**
     * Partes já enviadas a este upload e ainda não concluídas, ordenadas por número.
     * Vazio se o upload já foi concluído ou abortado.
     */
    default List<UploadedPart> listParts() {
        throw new UnsupportedOperationException("listParts não suportado por " + getClass().getSimpleName());
    }

    /**
     * Partes do objeto gerado por {@link #complete}, ordenadas por número. Só S3 e Azure
     * guardam essa informação depois do commit; os demais lançam {@link UnsupportedOperationException}.
     */
    default List<UploadedPart> listCompletedParts() {
        throw new UnsupportedOperationException("listCompletedParts não suportado por " + getClass().getSimpleName());
    }
}
