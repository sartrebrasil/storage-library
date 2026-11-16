package com.example.storage.spring;

import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

/**
 * As chaves de conexão que saíram de {@code storage.*} (design de 2026-11-15). O Spring Boot ignora chave desconhecida
 * por padrão, e uma {@code storage.s3.endpoint} ignorada faria a aplicação falar com a AWS real em vez do LocalStack:
 * cada uma derruba o startup, com o que declarar no lugar, sempre (mesmo sem {@code storage.provider}).
 */
final class RemovedKeys {

    /** Uma seção de {@code storage.*}: as chaves removidas (sem hífen, minúsculas, com o nome como se escreve) e o substituto. */
    private record Section(Map<String, String> removed, String replacement) {

        /** O nome da chave como se escreve, ou {@code null} se ela continua válida. */
        String display(String key) {
            if (removed.isEmpty()) {
                return key;
            }
            return removed.get(normalize(key));
        }
    }

    private static final Map<String, Section> SECTIONS = new LinkedHashMap<>();

    static {
        SECTIONS.put("s3", new Section(names("region", "endpoint", "path-style", "access-key", "secret-key",
                "async-credential-update"), "os beans S3Client e S3Presigner"));
        // a seção inteira saiu: qualquer chave de storage.gcs.* conta
        SECTIONS.put("gcs", new Section(Map.of(), "os beans Storage e MultipartUploadClient"));
        SECTIONS.put("azure", new Section(names("connection-string", "endpoint"), "o bean BlobServiceClient"));
        SECTIONS.put("oci", new Section(names("config-file", "profile"),
                "o bean com.oracle.bmc.objectstorage.ObjectStorage"));
        SECTIONS.put("sftp", new Section(names("host", "port", "username", "password", "private-key-path",
                "known-hosts", "insecure-trust-all-hosts", "keep-alive", "max-channels"), "o bean SftpConnection"));
    }

    private RemovedKeys() {
    }

    private static Map<String, String> names(String... keys) {
        Map<String, String> names = new LinkedHashMap<>();
        for (String key : keys) {
            names.put(normalize(key), key);
        }
        return names;
    }

    /**
     * Sem hífen, sublinhado nem ponto e em minúsculas, para valer também na forma de variável de ambiente:
     * {@code STORAGE_S3_ACCESS_KEY} chega ao {@code Binder} como {@code access.key}, e {@code STORAGE_S3_ACCESSKEY} como
     * {@code accesskey}; as duas ligavam {@code storage.s3.access-key}.
     */
    private static String normalize(String key) {
        return key.replace("-", "").replace("_", "").replace(".", "").toLowerCase(Locale.ROOT);
    }

    /** As chaves do mapa, com as aninhadas ({@code access} -> {@code key}) juntadas por ponto. */
    private static List<String> flatten(String prefix, Map<?, ?> map) {
        List<String> keys = new ArrayList<>();
        map.forEach((key, value) -> {
            String name = prefix.isEmpty() ? String.valueOf(key) : prefix + "." + key;
            if (value instanceof Map<?, ?> nested && !nested.isEmpty()) {
                keys.addAll(flatten(name, nested));
            } else {
                keys.add(name);
            }
        });
        return keys;
    }

    /** @throws IllegalStateException com todas as chaves removidas presentes, numa mensagem só */
    static void check(Environment environment) {
        Binder binder = Binder.get(environment);
        List<String> problems = new ArrayList<>();
        SECTIONS.forEach((name, section) -> {
            Map<String, Object> keys = binder.bind("storage." + name, Bindable.mapOf(String.class, Object.class))
                    .orElse(Map.of());
            new TreeSet<>(flatten("", keys)).forEach(key -> {
                String display = section.display(key);
                if (display != null) {
                    problems.add("storage." + name + "." + display + " foi removida: configure " + section.replacement()
                            + " na aplicação (veja \"Migrando\" no README)");
                }
            });
        });
        if (!problems.isEmpty()) {
            throw new IllegalStateException("Configuração inválida de storage.*:\n - " + String.join("\n - ", problems));
        }
    }
}
