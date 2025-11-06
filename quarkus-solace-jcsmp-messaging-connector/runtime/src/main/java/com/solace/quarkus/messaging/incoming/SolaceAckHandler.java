package com.solace.quarkus.messaging.incoming;

import java.util.concurrent.CompletionStage;

import io.smallrye.mutiny.Uni;

class SolaceAckHandler {

    public CompletionStage<Void> handle(SolaceInboundMessage<?> msg) {
        return Uni.createFrom().voidItem()
                .invoke(() -> msg.getMessage().ackMessage())
                .runSubscriptionOn(msg::runOnMessageContext)
                .subscribeAsCompletionStage();
    }
}
