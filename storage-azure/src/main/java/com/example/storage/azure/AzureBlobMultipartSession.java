package com.example.storage.azure;

import com.azure.core.exception.AzureException;
import com.azure.core.util.BinaryData;
import com.azure.core.util.Context;
import com.azure.storage.blob.models.BlobHttpHeaders;
import com.azure.storage.blob.options.BlockBlobCommitBlockListOptions;
import com.azure.storage.blob.options.BlockBlobStageBlockOptions;
import com.azure.storage.blob.specialized.BlockBlobClient;
import com.example.storage.MultipartSession;
import com.example.storage.ObjectMetadata;
import com.example.storage.StorageException;
import com.example.storage.UploadedPart;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;

final class AzureBlobMultipartSession implements MultipartSession {

    private final BlockBlobClient blob;
    private final String key;
    private final String uploadId;
    private final ObjectMetadata metadata;

    AzureBlobMultipartSession(BlockBlobClient blob, String key, String uploadId, ObjectMetadata metadata) {
        this.blob = blob;
        this.key = key;
        this.uploadId = uploadId;
        this.metadata = metadata;
    }

    @Override
    public String key() {
        return key;
    }

    @Override
    public String uploadId() {
        return uploadId;
    }

    @Override
    public UploadedPart uploadPart(int partNumber, byte[] data, int length) {
        // O Azure rejeita bloco vazio. Parte vazia só acontece no relatório
        // sem nenhum byte; o commit com lista vazia cria o blob de 0 bytes.
        if (length == 0) {
            return new UploadedPart(partNumber, null, null);
        }
        String blockId = blockId(partNumber);
        byte[] md5 = md5(data, length);
        try {
            // BinaryData sobre ByteBuffer é reenviável nos retries sem copiar o buffer.
            // O Content-MD5 faz o servidor validar a integridade do bloco.
            BinaryData body = BinaryData.fromByteBuffer(ByteBuffer.wrap(data, 0, length));
            blob.stageBlockWithResponse(new BlockBlobStageBlockOptions(blockId, body).setContentMd5(md5),
                    null, Context.NONE);
            return new UploadedPart(partNumber, blockId, Base64.getEncoder().encodeToString(md5));
        } catch (AzureException e) {
            throw new StorageException("Falha ao enviar parte " + partNumber + " de " + key, e);
        }
    }

    @Override
    public void complete(List<UploadedPart> parts) {
        List<String> blockIds = parts.stream()
                .filter(p -> p.etag() != null)
                .sorted(Comparator.comparingInt(UploadedPart::partNumber))
                .map(UploadedPart::etag)
                .toList();
        BlockBlobCommitBlockListOptions options = new BlockBlobCommitBlockListOptions(blockIds)
                .setHeaders(new BlobHttpHeaders()
                        .setContentType(metadata.contentType())
                        .setContentDisposition(metadata.contentDisposition()))
                .setMetadata(metadata.userMetadata());
        try {
            blob.commitBlockListWithResponse(options, null, Context.NONE);
        } catch (AzureException e) {
            throw new StorageException("Falha ao concluir upload de " + key, e);
        }
    }

    /**
     * O Azure não permite apagar blocos não commitados: eles são descartados
     * automaticamente após 7 dias (ou no próximo commit do mesmo blob). Apagar
     * o blob aqui destruiria uma versão já publicada, então é no-op.
     */
    @Override
    public void abort() {
    }

    /** Ids de bloco precisam ter o mesmo tamanho no blob; o uploadId evita colisão entre uploads. */
    private String blockId(int partNumber) {
        String raw = uploadId + "-" + String.format("%05d", partNumber);
        return Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.US_ASCII));
    }

    private static byte[] md5(byte[] data, int length) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            digest.update(data, 0, length);
            return digest.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 indisponível na JVM", e);
        }
    }
}
