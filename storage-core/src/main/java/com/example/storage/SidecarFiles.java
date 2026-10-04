package com.example.storage;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;

/**
 * Layout comum dos adapters sobre sistemas de arquivos (filesystem local e SFTP): um arquivo por objeto,
 * com metadata e versão num sidecar {@code <arquivo>.objmeta} (formato {@link Properties}), escrita por
 * temporários {@code .pending-*} e uploads multipart em {@code .uploads/}. Uso interno dos adapters; não
 * faz parte da API de {@link ObjectStorage}.
 */
public final class SidecarFiles {

    public static final String META_SUFFIX = ".objmeta";
    public static final String TEMP_PREFIX = ".pending-";
    public static final String UPLOADS_DIR = ".uploads";

    private static final String USER_PREFIX = "user.";

    private SidecarFiles() {
    }

    /**
     * Rejeita chaves que não viram um único arquivo dentro do root: absoluta, com {@code \}, segmento
     * vazio, {@code .} ou {@code ..}, segmento terminado em ponto ou espaço (o Windows os descarta, e duas
     * chaves virariam o mesmo arquivo) e os nomes reservados deste layout.
     *
     * @throws IllegalArgumentException chave inválida
     */
    public static void requireValidKey(String key) {
        Objects.requireNonNull(key, "key");
        if (key.isEmpty() || key.startsWith("/") || key.contains("\\")) {
            throw new IllegalArgumentException("Chave inválida: " + key);
        }
        if (key.equals(UPLOADS_DIR) || key.startsWith(UPLOADS_DIR + "/")) {
            throw new IllegalArgumentException("Chave usa o prefixo reservado " + UPLOADS_DIR + ": " + key);
        }
        for (String segment : key.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")
                    || segment.endsWith(".") || segment.endsWith(" ")) {
                throw new IllegalArgumentException("Chave inválida (vira outro arquivo ou escapa do root): " + key);
            }
            if (segment.endsWith(META_SUFFIX) || segment.startsWith(TEMP_PREFIX)) {
                throw new IllegalArgumentException("Chave usa um nome reservado (" + META_SUFFIX + " ou "
                        + TEMP_PREFIX + "): " + key);
            }
        }
    }

    /** {@code true} para um arquivo de dados; {@code false} para sidecars e temporários. */
    public static boolean isDataFile(String fileName) {
        return !fileName.endsWith(META_SUFFIX) && !fileName.startsWith(TEMP_PREFIX);
    }

    /**
     * O conteúdo do sidecar: versão, metadata e o tamanho e a data de modificação do arquivo que ele
     * descreve, na unidade que o adapter usa para comparar em {@link #describing}.
     */
    public static byte[] encode(ObjectMetadata metadata, String version, long size, long lastModified) {
        Properties props = new Properties();
        props.setProperty("version", version);
        props.setProperty("size", String.valueOf(size));
        props.setProperty("mtime", String.valueOf(lastModified));
        if (metadata.contentType() != null) {
            props.setProperty("contentType", metadata.contentType());
        }
        if (metadata.contentDisposition() != null) {
            props.setProperty("contentDisposition", metadata.contentDisposition());
        }
        metadata.userMetadata().forEach((k, v) -> props.setProperty(USER_PREFIX + k, v));
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try {
            props.store(buffer, null);
        } catch (IOException e) {
            throw new UncheckedIOException(e);   // em memória: não acontece
        }
        return buffer.toByteArray();
    }

    /**
     * {@code sidecar} se ele descreve o arquivo (mesmo tamanho e data de modificação), senão vazio: o arquivo
     * foi trocado por fora da API, ou o processo caiu entre gravar os dados e o sidecar. Sidecars gravados
     * antes de guardarem tamanho e data valem como estão.
     */
    public static Properties describing(Properties sidecar, long size, long lastModified) {
        String storedSize = sidecar.getProperty("size");
        String storedMtime = sidecar.getProperty("mtime");
        boolean stale = (storedSize != null && !storedSize.equals(String.valueOf(size)))
                || (storedMtime != null && !storedMtime.equals(String.valueOf(lastModified)));
        return stale ? new Properties() : sidecar;
    }

    /** A versão do sidecar ou, sem ele, uma derivada da data de modificação. */
    public static String versionOf(Properties sidecar, long lastModifiedMillis) {
        return sidecar.getProperty("version", "v" + lastModifiedMillis);
    }

    /** A metadata gravada no sidecar, como está (sem a validação de escrita). */
    public static ObjectMetadata metadataFrom(Properties sidecar) {
        Map<String, String> userMetadata = new LinkedHashMap<>();
        for (String name : sidecar.stringPropertyNames()) {
            if (name.startsWith(USER_PREFIX)) {
                userMetadata.put(name.substring(USER_PREFIX.length()), sidecar.getProperty(name));
            }
        }
        return new ObjectMetadata(sidecar.getProperty("contentType"), sidecar.getProperty("contentDisposition"),
                userMetadata);
    }
}
