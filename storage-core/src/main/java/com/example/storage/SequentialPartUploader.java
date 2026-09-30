package com.example.storage;

import java.util.ArrayList;
import java.util.List;

/** Um único buffer, reaproveitado parte a parte; cada parte sobe na thread que escreve. */
final class SequentialPartUploader implements PartUploader {

    private final MultipartSession session;
    private final List<UploadedPart> parts = new ArrayList<>();

    SequentialPartUploader(MultipartSession session) {
        this.session = session;
    }

    @Override
    public byte[] nextBuffer(byte[] sent) {
        return sent;   // a parte já subiu: o mesmo buffer serve para a próxima
    }

    @Override
    public void send(int partNumber, byte[] data, int length) {
        parts.add(session.uploadPart(partNumber, data, length));   // a falha sobe nesta mesma escrita
    }

    @Override
    public List<UploadedPart> awaitAll() {
        return parts;
    }

    @Override
    public void drain() {
        // nada em voo: cada parte terminou dentro do próprio send
    }
}
