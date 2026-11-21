package com.raikiservice.raf.wallet.web.dto;

import com.raikiservice.raf.wallet.domain.CodeChannel;

/** Code de retrait envoye : le message a afficher et le canal (SMS ou EMAIL). */
public record CodeSentResponse(String message, CodeChannel channel) {

    public static CodeSentResponse withdrawal(CodeChannel channel) {
        return new CodeSentResponse(channel == CodeChannel.EMAIL
                ? "Code de confirmation envoye par email."
                : "Code de confirmation envoye par SMS.", channel);
    }
}
