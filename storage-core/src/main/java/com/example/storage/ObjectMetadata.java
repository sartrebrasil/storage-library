package com.example.storage;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * Propriedades gravadas junto com o objeto.
 *
 * <p>Na escrita, as chaves de {@code userMetadata} são restritas a {@code [a-z_][a-z0-9_]*} e os
 * valores a ASCII imprimível: é o que os quatro provedores aceitam sem transformar
 * (o Azure exige identificadores C#, o S3 converte para minúsculas, a OCI prefixa).
 * {@link #requireWritable()} valida, e {@link PutOptions} e {@link ObjectStorage#initiateMultipart}
 * o chamam, então o erro aparece antes de qualquer envio.</p>
 *
 * <p>A construção não valida: {@link ObjectStorage#head} devolve a metadata como o provedor a guarda,
 * inclusive a gravada por outras ferramentas ({@code goog-reserved-file-mtime}, {@code s3cmd-attrs},
 * maiúsculas no Azure). Regravá-la falha em {@link #requireWritable()}.</p>
 */
public record ObjectMetadata(String contentType,
                             String contentDisposition,
                             Map<String, String> userMetadata) {

    private static final Pattern KEY = Pattern.compile("[a-z_][a-z0-9_]*");
    private static final Pattern VALUE = Pattern.compile("[\\x20-\\x7E]*");
    private static final Pattern DOWNLOAD_NAME = Pattern.compile("[\\x20-\\x7E&&[^\"\\\\]]+");

    public ObjectMetadata {
        userMetadata = userMetadata == null ? Map.of() : Map.copyOf(userMetadata);
    }

    /**
     * Confirma que esta metadata pode ser gravada em qualquer provedor.
     *
     * @return esta metadata
     * @throws IllegalArgumentException chave fora de {@code [a-z_][a-z0-9_]*} ou valor fora de ASCII imprimível
     */
    public ObjectMetadata requireWritable() {
        userMetadata.forEach((key, value) -> {
            if (!KEY.matcher(key).matches()) {
                throw new IllegalArgumentException("Chave de metadata inválida: '" + key
                        + "' (use [a-z_][a-z0-9_]*)");
            }
            if (!VALUE.matcher(value).matches()) {
                throw new IllegalArgumentException("Valor de metadata não-ASCII na chave '" + key + "'");
            }
        });
        return this;
    }

    public static ObjectMetadata of(String contentType) {
        return new ObjectMetadata(contentType, null, Map.of());
    }

    public static ObjectMetadata empty() {
        return new ObjectMetadata(null, null, Map.of());
    }

    /** Grava {@code Content-Disposition: attachment}: quem baixar salva como {@code fileName}. */
    public ObjectMetadata withDownloadName(String fileName) {
        return new ObjectMetadata(contentType, attachmentDisposition(fileName), userMetadata);
    }

    /**
     * {@code attachment; filename="<fileName>"}. O nome é restrito a ASCII imprimível, sem {@code "} nem
     * {@code \}: é o que cabe entre aspas sem escape e os quatro provedores devolvem sem transformar.
     *
     * @throws IllegalArgumentException nome vazio, fora de ASCII imprimível, ou com {@code "} ou {@code \}
     */
    public static String attachmentDisposition(String fileName) {
        if (fileName == null || !DOWNLOAD_NAME.matcher(fileName).matches()) {
            throw new IllegalArgumentException("Nome de download inválido: '" + fileName
                    + "' (ASCII imprimível, sem aspas nem barra invertida)");
        }
        return "attachment; filename=\"" + fileName + "\"";
    }

    public ObjectMetadata withUserMetadata(Map<String, String> metadata) {
        return new ObjectMetadata(contentType, contentDisposition, metadata);
    }
}
